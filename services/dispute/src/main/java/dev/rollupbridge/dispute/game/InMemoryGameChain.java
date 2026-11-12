package dev.rollupbridge.dispute.game;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.dispute.game.GameView.Phase;
import dev.rollupbridge.dispute.game.GameView.Resolution;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.ToyVm;
import dev.rollupbridge.dispute.vm.VmState;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A line-by-line Java model of {@code BisectionGame.sol}: the same checks in the same order, the same state
 * transitions and the same error names. It runs in memory (thousands of games per second), which is what makes the
 * randomized "an honest player always wins" tests affordable; {@code ModelVsContractTest} then checks the model
 * against the real contract on Anvil move by move, so the speed does not cost fidelity.
 */
public final class InMemoryGameChain implements GameChain {
    private static final class Game {
        String proposer;
        String challenger = GameView.NO_ADDRESS;
        Phase phase;
        Resolution resolution = Resolution.NONE;
        boolean proposerWon;
        long lo;
        long hi;
        long deadline;
        Bytes32 loHash;
        Bytes32 hiHash;
        Bytes32 midHash = Bytes32.ZERO;

        GameView view() {
            return new GameView(proposer, challenger, phase, resolution, proposerWon, lo, hi, deadline, loHash, hiHash, midHash);
        }
    }

    private final Bytes32 programHash;
    private final long moveTimeout;
    private final BigInteger proposerBond;
    private final BigInteger challengerBond;
    private final List<Game> games = new ArrayList<>();
    private final Map<String, BigInteger> credits = new HashMap<>();
    private long now;

    public InMemoryGameChain(Bytes32 programHash, long moveTimeout, BigInteger proposerBond, BigInteger challengerBond, long startTime) {
        this.programHash = programHash;
        this.moveTimeout = moveTimeout;
        this.proposerBond = proposerBond;
        this.challengerBond = challengerBond;
        this.now = startTime;
    }

    private static String addr(String a) {
        return a.toLowerCase(Locale.ROOT);
    }

    private Game get(long id) {
        if (id < 0 || id >= games.size()) {
            throw new GameException("UnknownGame", Long.toString(id));
        }
        return games.get((int) id);
    }

    private void requireOnTime(Game g) {
        if (now > g.deadline) {
            throw new GameException("MoveTimeExpired");
        }
    }

    @Override
    public long createGame(String sender, Bytes32 initialHash, long steps, Bytes32 claimedHash) {
        if (steps == 0) {
            throw new GameException("ZeroSteps");
        }
        Game g = new Game();
        g.proposer = addr(sender);
        g.phase = Phase.AWAITING_CHALLENGE;
        g.hi = steps;
        g.deadline = now + moveTimeout;
        g.loHash = initialHash;
        g.hiHash = claimedHash;
        games.add(g);
        return games.size() - 1L;
    }

    @Override
    public void challenge(String sender, long id) {
        Game g = get(id);
        if (g.phase != Phase.AWAITING_CHALLENGE) {
            throw new GameException("WrongPhase", g.phase.name());
        }
        requireOnTime(g);
        g.challenger = addr(sender);
        g.phase = phaseAfterNarrowing(g);
        g.deadline = now + moveTimeout;
    }

    @Override
    public void bisect(String sender, long id, long step, Bytes32 midHash) {
        Game g = get(id);
        if (g.phase != Phase.AWAITING_BISECT) {
            throw new GameException("WrongPhase", g.phase.name());
        }
        if (!addr(sender).equals(g.proposer)) {
            throw new GameException("NotProposer");
        }
        requireOnTime(g);
        long expected = mid(g);
        if (step != expected) {
            throw new GameException("WrongStep", expected + " vs " + step);
        }
        g.midHash = midHash;
        g.phase = Phase.AWAITING_RESPONSE;
        g.deadline = now + moveTimeout;
    }

    @Override
    public void respond(String sender, long id, boolean agree) {
        Game g = get(id);
        if (g.phase != Phase.AWAITING_RESPONSE) {
            throw new GameException("WrongPhase", g.phase.name());
        }
        if (!addr(sender).equals(g.challenger)) {
            throw new GameException("NotChallenger");
        }
        requireOnTime(g);
        long mid = mid(g);
        if (agree) {
            g.lo = mid;
            g.loHash = g.midHash;
        } else {
            g.hi = mid;
            g.hiHash = g.midHash;
        }
        g.midHash = Bytes32.ZERO;
        g.phase = phaseAfterNarrowing(g);
        g.deadline = now + moveTimeout;
    }

    @Override
    public void step(String sender, long id, Program program, VmState preState) {
        Game g = get(id);
        if (g.phase != Phase.AWAITING_STEP) {
            throw new GameException("WrongPhase", g.phase.name());
        }
        requireOnTime(g);
        if (!program.hash().equals(programHash)) {
            throw new GameException("ProgramMismatch");
        }
        if (!preState.hash().equals(g.loHash)) {
            throw new GameException("PreStateMismatch");
        }
        Bytes32 computed = ToyVm.step(program, preState).hash();
        settle(g, computed.equals(g.hiHash), Resolution.ONE_STEP_PROOF);
    }

    @Override
    public void claimTimeout(String sender, long id) {
        Game g = get(id);
        Phase phase = g.phase;
        if (phase == Phase.RESOLVED) {
            throw new GameException("WrongPhase", phase.name());
        }
        if (now <= g.deadline) {
            throw new GameException("TimeoutNotReached");
        }
        boolean proposerWon = phase != Phase.AWAITING_BISECT;
        settle(g, proposerWon, phase == Phase.AWAITING_CHALLENGE ? Resolution.UNCHALLENGED : Resolution.TIMEOUT);
    }

    @Override
    public BigInteger withdraw(String sender) {
        BigInteger amount = credits.getOrDefault(addr(sender), BigInteger.ZERO);
        if (amount.signum() == 0) {
            throw new GameException("NothingToWithdraw");
        }
        credits.put(addr(sender), BigInteger.ZERO);
        return amount;
    }

    private static long mid(Game g) {
        return g.lo + (g.hi - g.lo) / 2;
    }

    private static Phase phaseAfterNarrowing(Game g) {
        return g.hi - g.lo == 1 ? Phase.AWAITING_STEP : Phase.AWAITING_BISECT;
    }

    private void settle(Game g, boolean proposerWon, Resolution resolution) {
        g.phase = Phase.RESOLVED;
        g.resolution = resolution;
        g.proposerWon = proposerWon;
        if (g.challenger.equals(GameView.NO_ADDRESS)) {
            credit(g.proposer, proposerBond);
        } else if (proposerWon) {
            credit(g.proposer, proposerBond.add(challengerBond));
        } else {
            credit(g.challenger, proposerBond.add(challengerBond));
        }
    }

    private void credit(String account, BigInteger amount) {
        credits.merge(account, amount, BigInteger::add);
    }

    @Override
    public GameView game(long id) {
        return get(id).view();
    }

    @Override
    public long gameCount() {
        return games.size();
    }

    @Override
    public BigInteger credits(String account) {
        return credits.getOrDefault(addr(account), BigInteger.ZERO);
    }

    @Override
    public BigInteger proposerBond() {
        return proposerBond;
    }

    @Override
    public BigInteger challengerBond() {
        return challengerBond;
    }

    @Override
    public long moveTimeout() {
        return moveTimeout;
    }

    @Override
    public long now() {
        return now;
    }

    @Override
    public void advanceTime(long seconds) {
        now += seconds;
    }
}
