package dev.rollupbridge.dispute.bot;

import dev.rollupbridge.dispute.game.GameChain;
import dev.rollupbridge.dispute.game.GameView;
import java.util.List;

/**
 * Plays one game to its end: opens it with the proposer, then lets the players move in turn until the game is
 * resolved. If nobody can or wants to move, the clock runs out and a bystander claims the timeout, exactly what a
 * keeper would do on-chain.
 */
public final class GameRunner {
    private static final int MAX_ROUNDS = 10_000;

    private GameRunner() {}

    /** The result of a played game. {@code moves} counts every successful on-chain call, creation and timeout included. */
    public record Outcome(long gameId, long moves, boolean proposerWon, GameView.Resolution resolution, GameView last) {}

    /**
     * @param players who may move, in the order they get the chance (the proposer included)
     * @param bystander account that claims timeouts
     */
    public static Outcome play(GameChain chain, ProposerBot proposer, long steps, List<Player> players, String bystander) {
        long id = proposer.open(chain, steps);
        long moves = 1;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            GameView v = chain.game(id);
            if (v.resolved()) {
                return new Outcome(id, moves, v.proposerWon(), v.resolution(), v);
            }
            boolean moved = false;
            for (Player p : players) {
                if (p.act(chain, id)) {
                    moves++;
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                chain.advanceTime(chain.moveTimeout() + 1);
                chain.claimTimeout(bystander, id);
                moves++;
            }
        }
        throw new IllegalStateException("game " + id + " did not end within " + MAX_ROUNDS + " rounds");
    }

    /** The on-chain call budget of a disputed game: create + challenge + 2 per halving + one step proof. */
    public static long maxMovesForDisputedGame(long steps) {
        long halvings = 64 - Long.numberOfLeadingZeros(steps - 1); // ceil(log2 steps), 0 for steps = 1
        return 2 * halvings + 3;
    }
}
