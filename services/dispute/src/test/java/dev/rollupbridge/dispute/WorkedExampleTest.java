package dev.rollupbridge.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.dispute.bot.ChallengerBot;
import dev.rollupbridge.dispute.bot.Claims;
import dev.rollupbridge.dispute.bot.GameRunner;
import dev.rollupbridge.dispute.bot.GameRunner.Outcome;
import dev.rollupbridge.dispute.bot.ProposerBot;
import dev.rollupbridge.dispute.game.GameChain;
import dev.rollupbridge.dispute.game.GameView;
import dev.rollupbridge.dispute.game.InMemoryGameChain;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.ToyVm;
import dev.rollupbridge.dispute.vm.Trace;
import dev.rollupbridge.dispute.vm.VmState;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The worked example of docs/DISPUTE-GAME.md: a 12-step computation, a proposer that corrupts the state at step 7 and
 * an honest challenger. The test prints the whole game; its output is pasted into the document.
 */
class WorkedExampleTest {

    @Test
    @DisplayName("worked example: 12 steps, the proposer corrupts step 7, the challenger pinpoints step 6 -> 7")
    void workedExample() {
        Program program = Program.sumLoop(3);
        Trace truth = new Trace(program, VmState.zero());
        long steps = 12;
        InMemoryGameChain model = RandomGamesTest.chain(program);
        LoggingChain chain = new LoggingChain(model, truth);

        System.out.println("program: sum 3+2+1 into r1 (7 instructions), hash " + short32(program.hash()));
        for (long i = 0; i <= steps; i++) {
            System.out.println("  true state after " + i + " steps: " + truth.at(i));
        }
        ProposerBot proposer = new ProposerBot(RandomGamesTest.PROPOSER, Claims.consistentLieFrom(truth, 7), truth);
        ChallengerBot challenger = ChallengerBot.honest(RandomGamesTest.CHALLENGER, truth);
        Outcome out = GameRunner.play(chain, proposer, steps, List.of(proposer, challenger), RandomGamesTest.BYSTANDER);

        System.out.println("result: proposerWon=" + out.proposerWon() + " via " + out.resolution() + " in " + out.moves() + " moves");
        System.out.println("contested transition: step " + out.last().lo() + " -> " + out.last().hi());

        assertThat(out.proposerWon()).isFalse();
        assertThat(out.last().lo()).isEqualTo(6);
        assertThat(out.last().hi()).isEqualTo(7);
        assertThat(model.credits(RandomGamesTest.CHALLENGER)).isEqualTo(BigInteger.TEN.pow(18).add(BigInteger.TEN.pow(18).divide(BigInteger.TWO)));
        assertThat(ToyVm.run(program, VmState.zero(), steps).r1()).isEqualTo(6);
    }

    private static String short32(Bytes32 h) {
        return h.hex().substring(0, 10) + "..";
    }

    /** Prints every move with the interval it acts on. */
    private static final class LoggingChain implements GameChain {
        private final InMemoryGameChain inner;
        private final Trace truth;

        LoggingChain(InMemoryGameChain inner, Trace truth) {
            this.inner = inner;
            this.truth = truth;
        }

        private String interval(long id) {
            GameView v = inner.game(id);
            return "(lo=" + v.lo() + ", hi=" + v.hi() + ")";
        }

        @Override
        public long createGame(String sender, Bytes32 initialHash, long steps, Bytes32 claimedHash) {
            long id = inner.createGame(sender, initialHash, steps, claimedHash);
            System.out.println("1 proposer  createGame: after " + steps + " steps the state hash is " + short32(claimedHash)
                    + (claimedHash.equals(truth.hashAt(steps)) ? " (true)" : " (FALSE)") + "  bond 1.0 ETH");
            return id;
        }

        @Override
        public void challenge(String sender, long id) {
            inner.challenge(sender, id);
            System.out.println("2 challenger challenge: disputes the claim, bond 0.5 ETH, interval " + interval(id));
        }

        @Override
        public void bisect(String sender, long id, long step, Bytes32 midHash) {
            String before = interval(id);
            inner.bisect(sender, id, step, midHash);
            System.out.println("  proposer  bisect " + before + ": claims step " + step + " = " + short32(midHash)
                    + (midHash.equals(truth.hashAt(step)) ? " (true)" : " (FALSE)"));
        }

        @Override
        public void respond(String sender, long id, boolean agree) {
            inner.respond(sender, id, agree);
            System.out.println("  challenger respond: " + (agree ? "agrees, lower bound moves up" : "disagrees, upper bound moves down")
                    + " -> interval " + interval(id));
        }

        @Override
        public void step(String sender, long id, Program program, VmState preState) {
            GameView v = inner.game(id);
            inner.step(sender, id, program, preState);
            System.out.println("  challenger step: executes ONE instruction of the program on " + preState + " (step " + v.lo()
                    + "), gets " + ToyVm.step(program, preState) + " -> hash " + short32(ToyVm.step(program, preState).hash())
                    + ", the proposer claimed " + short32(v.hiHash()));
        }

        @Override
        public void claimTimeout(String sender, long id) {
            inner.claimTimeout(sender, id);
        }

        @Override
        public BigInteger withdraw(String sender) {
            return inner.withdraw(sender);
        }

        @Override
        public GameView game(long id) {
            return inner.game(id);
        }

        @Override
        public long gameCount() {
            return inner.gameCount();
        }

        @Override
        public BigInteger credits(String account) {
            return inner.credits(account);
        }

        @Override
        public BigInteger proposerBond() {
            return inner.proposerBond();
        }

        @Override
        public BigInteger challengerBond() {
            return inner.challengerBond();
        }

        @Override
        public long moveTimeout() {
            return inner.moveTimeout();
        }

        @Override
        public long now() {
            return inner.now();
        }

        @Override
        public void advanceTime(long seconds) {
            inner.advanceTime(seconds);
        }
    }
}
