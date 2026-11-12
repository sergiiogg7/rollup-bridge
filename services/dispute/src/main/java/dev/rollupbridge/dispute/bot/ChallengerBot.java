package dev.rollupbridge.dispute.bot;

import dev.rollupbridge.dispute.game.GameChain;
import dev.rollupbridge.dispute.game.GameException;
import dev.rollupbridge.dispute.game.GameView;
import dev.rollupbridge.dispute.vm.Trace;
import dev.rollupbridge.dispute.vm.VmState;
import java.util.Random;

/**
 * The challenger. {@link Mode#HONEST} recomputes the real execution itself (it never trusts the proposer's claims):
 * it challenges only a claim that is wrong, agrees only with midpoints that are right, and proves the final step. The
 * other modes are lying or broken challengers that try to make an honest proposer lose.
 */
public final class ChallengerBot implements Player {
    /** How the challenger plays. */
    public enum Mode {
        /** Verifies everything against its own execution. */
        HONEST,
        /** Disputes every claim, true or not, and disagrees with every midpoint. */
        ALWAYS_DISAGREE,
        /** Disputes every claim and agrees with every midpoint. */
        ALWAYS_AGREE,
        /** Disputes every claim and answers every midpoint with a coin flip. */
        RANDOM,
        /** Plays like ALWAYS_DISAGREE but submits the final proof with a corrupted pre-state. */
        WRONG_PRE_STATE,
        /** Disputes every claim and then never moves again (offline). */
        SILENT_AFTER_CHALLENGE
    }

    private final String address;
    private final Trace truth;
    private final Mode mode;
    private final Random rnd;
    private boolean forgedTried;

    public ChallengerBot(String address, Trace truth, Mode mode, long seed) {
        this.address = address;
        this.truth = truth;
        this.mode = mode;
        this.rnd = new Random(seed);
    }

    public static ChallengerBot honest(String address, Trace truth) {
        return new ChallengerBot(address, truth, Mode.HONEST, 0);
    }

    @Override
    public String address() {
        return address;
    }

    @Override
    public boolean act(GameChain chain, long id) {
        GameView v = chain.game(id);
        switch (v.phase()) {
            case AWAITING_CHALLENGE:
                if (mode == Mode.HONEST && v.hiHash().equals(truth.hashAt(v.hi()))) {
                    return false; // the claim is right: nothing to dispute
                }
                chain.challenge(address, id);
                return true;
            case AWAITING_RESPONSE:
                if (!isMe(v) || mode == Mode.SILENT_AFTER_CHALLENGE) {
                    return false;
                }
                chain.respond(address, id, agree(v));
                return true;
            case AWAITING_STEP:
                return proveStep(chain, id, v);
            default:
                return false;
        }
    }

    private boolean agree(GameView v) {
        return switch (mode) {
            case HONEST -> v.midHash().equals(truth.hashAt(v.expectedStep()));
            case ALWAYS_AGREE -> true;
            case RANDOM -> rnd.nextBoolean();
            default -> false;
        };
    }

    private boolean proveStep(GameChain chain, long id, GameView v) {
        if (!isMe(v) || mode == Mode.SILENT_AFTER_CHALLENGE) {
            return false;
        }
        VmState pre = truth.at(v.lo());
        if (mode == Mode.HONEST) {
            // only worth sending when the recorded claim at `hi` is wrong; an honest proposer proves its own step
            if (v.hiHash().equals(truth.hashAt(v.hi()))) {
                return false;
            }
        } else if (mode == Mode.WRONG_PRE_STATE) {
            if (forgedTried) {
                return false;
            }
            forgedTried = true;
            try {
                chain.step(address, id, truth.program(), pre.withReg(0, pre.r0() + 1));
            } catch (GameException expected) {
                // refused: the forged pre-state does not hash to the agreed one. Not a move; the game is unchanged.
            }
            return false;
        }
        chain.step(address, id, truth.program(), pre);
        return true;
    }

    private boolean isMe(GameView v) {
        return v.challenger().equalsIgnoreCase(address);
    }
}
