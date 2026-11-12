package dev.rollupbridge.dispute.bot;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.dispute.bot.Claims.ClaimFunction;
import dev.rollupbridge.dispute.game.GameChain;
import dev.rollupbridge.dispute.game.GameView;
import dev.rollupbridge.dispute.game.GameView.Phase;
import dev.rollupbridge.dispute.vm.Trace;

/**
 * The proposer: opens a game with a claim and defends it with whatever its {@link ClaimFunction} says. With the honest
 * function it is the honest proposer; with a lying function it is one of the lying strategies. It is rational about
 * the final step: it only submits the one-step proof if the recorded claim really is the result of the contested step
 * (the proof can only hurt a liar).
 */
public final class ProposerBot implements Player {
    private final String address;
    private final ClaimFunction claims;
    private final Trace truth; // the real execution, used only to decide whether the final step would win
    private final int maxMoves; // goes silent after this many bisections (Integer.MAX_VALUE = never)
    private int bisections;

    public ProposerBot(String address, ClaimFunction claims, Trace truth, int maxMoves) {
        this.address = address;
        this.claims = claims;
        this.truth = truth;
        this.maxMoves = maxMoves;
    }

    public ProposerBot(String address, ClaimFunction claims, Trace truth) {
        this(address, claims, truth, Integer.MAX_VALUE);
    }

    @Override
    public String address() {
        return address;
    }

    /** Opens the game: the claim about the final state after {@code steps} steps. */
    public long open(GameChain chain, long steps) {
        return chain.createGame(address, truth.initial().hash(), steps, claims.at(steps));
    }

    @Override
    public boolean act(GameChain chain, long id) {
        GameView v = chain.game(id);
        if (!v.proposer().equalsIgnoreCase(address)) {
            return false;
        }
        if (v.phase() == Phase.AWAITING_BISECT && bisections < maxMoves) {
            long mid = v.expectedStep();
            Bytes32 claim = claims.at(mid);
            chain.bisect(address, id, mid, claim);
            bisections++;
            return true;
        }
        if (v.phase() == Phase.AWAITING_STEP) {
            boolean claimIsRight =
                    v.loHash().equals(truth.hashAt(v.lo())) && v.hiHash().equals(truth.hashAt(v.hi()));
            if (claimIsRight) {
                chain.step(address, id, truth.program(), truth.at(v.lo()));
                return true;
            }
        }
        return false;
    }
}
