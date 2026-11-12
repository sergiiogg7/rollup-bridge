package dev.rollupbridge.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.dispute.bot.ChallengerBot;
import dev.rollupbridge.dispute.bot.Claims;
import dev.rollupbridge.dispute.bot.Claims.ClaimFunction;
import dev.rollupbridge.dispute.bot.GameRunner;
import dev.rollupbridge.dispute.bot.GameRunner.Outcome;
import dev.rollupbridge.dispute.bot.ProposerBot;
import dev.rollupbridge.dispute.game.InMemoryGameChain;
import dev.rollupbridge.dispute.vm.Trace;
import dev.rollupbridge.dispute.vm.VmState;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The game costs O(log N) on-chain moves, however long the disputed computation is. */
class LogarithmicCostTest {
    private static Outcome disputedGame(long steps, ClaimFunction claims, Trace truth) {
        InMemoryGameChain chain = RandomGamesTest.chain(truth.program());
        ProposerBot proposer = new ProposerBot(RandomGamesTest.PROPOSER, claims, truth);
        ChallengerBot challenger = ChallengerBot.honest(RandomGamesTest.CHALLENGER, truth);
        return GameRunner.play(chain, proposer, steps, List.of(proposer, challenger), RandomGamesTest.BYSTANDER);
    }

    @Test
    @DisplayName("N = 2^k steps: exactly 2k + 3 moves (create, challenge, k bisect+respond rounds, one step proof) for k = 0..62")
    void powersOfTwoCostExactlyTwoKPlusThree() {
        Trace truth = new Trace(RandomGamesTest.HALTS_EARLY, VmState.zero());
        Random rnd = new Random(1);
        for (int k = 0; k <= 62; k++) {
            long steps = 1L << k;
            for (ClaimFunction lie : List.of(
                    Claims.wrongFrom(truth, steps, 1), // only the final state is false
                    Claims.wrongFrom(truth, 1, 2), // everything but the initial state is false
                    Claims.consistentLieFrom(truth, Math.min(steps, 1 + (long) (rnd.nextDouble() * steps))))) {
                if (lie.at(steps).equals(truth.hashAt(steps))) {
                    continue; // a corruption the program later overwrites (MOV) converges to the truth: not a lie
                }
                Outcome out = disputedGame(steps, lie, truth);
                assertThat(out.proposerWon()).as("N = 2^%d, resolution %s, last %s", k, out.resolution(), out.last()).isFalse();
                assertThat(out.moves()).as("N = 2^%d", k).isEqualTo(2L * k + 3);
            }
        }
    }

    @Test
    @DisplayName("any N: between 2*floor(log2 N)+3 and 2*ceil(log2 N)+3 moves; a linear replay would need N")
    void arbitraryLengthsStayWithinTheLogarithmicBand() {
        Trace truth = RandomGamesTest.TRUTH_EARLY;
        Random rnd = new Random(2);
        for (int i = 0; i < 2000; i++) {
            long steps = 1 + (long) (rnd.nextDouble() * (1L << (1 + rnd.nextInt(50))));
            Outcome out = disputedGame(steps, Claims.wrongFrom(truth, Math.min(steps, 1 + (long) (rnd.nextDouble() * steps)), i), truth);
            long floorLog2 = 63 - Long.numberOfLeadingZeros(steps);
            assertThat(out.proposerWon()).isFalse();
            assertThat(out.moves()).as("N = %d", steps)
                    .isBetween(2 * floorLog2 + 3, GameRunner.maxMovesForDisputedGame(steps));
        }
    }

    @Test
    void aBillionStepsNeedsAboutSixtyMoves() {
        Trace truth = new Trace(RandomGamesTest.HALTS_EARLY, VmState.zero());
        long steps = 1_000_000_000L;
        Outcome out = disputedGame(steps, Claims.consistentLieFrom(truth, 123_456_789), truth);
        assertThat(out.proposerWon()).isFalse();
        assertThat(out.moves()).isBetween(61L, 63L); // 2 * 30 + 3 = 63 at most
    }
}
