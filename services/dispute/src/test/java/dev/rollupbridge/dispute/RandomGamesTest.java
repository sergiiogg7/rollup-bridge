package dev.rollupbridge.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.dispute.bot.ChallengerBot;
import dev.rollupbridge.dispute.bot.ChallengerBot.Mode;
import dev.rollupbridge.dispute.bot.Claims;
import dev.rollupbridge.dispute.bot.Claims.ClaimFunction;
import dev.rollupbridge.dispute.bot.GameRunner;
import dev.rollupbridge.dispute.bot.GameRunner.Outcome;
import dev.rollupbridge.dispute.bot.ProposerBot;
import dev.rollupbridge.dispute.game.GameChain;
import dev.rollupbridge.dispute.game.GameView.Resolution;
import dev.rollupbridge.dispute.game.InMemoryGameChain;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.Trace;
import dev.rollupbridge.dispute.vm.VmState;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Randomized games on the in-memory model. The property under test is the whole point of a dispute game: the HONEST
 * side wins, whatever the other side does and however long the computation is, and it wins in O(log N) moves.
 */
class RandomGamesTest {
    static final String PROPOSER = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";
    static final String CHALLENGER = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";
    static final String BYSTANDER = "0x9965507d1a55bcc2695c58ba16fb37d819b0a4dc";
    static final BigInteger PB = BigInteger.TEN.pow(18);
    static final BigInteger CB = PB.divide(BigInteger.TWO);

    /** Programs that run far longer than any N used below, plus one that halts early (so N can be astronomically big). */
    static final Program LONG_RUNNING = Program.sumLoop(1L << 30);
    static final Program HALTS_EARLY = Program.sumLoop(5);
    /** Shared true executions (a Trace caches its checkpoints, so thousands of games reuse one computation). */
    static final Trace TRUTH_LONG = new Trace(LONG_RUNNING, VmState.zero());
    static final Trace TRUTH_EARLY = new Trace(HALTS_EARLY, VmState.zero());
    /** The long-running program is only played for N <= 65536 (its true execution is really computed). */
    static final long LONG_RUNNING_MAX_STEPS = 65_536;

    static Trace truthFor(Program p) {
        return p == LONG_RUNNING ? TRUTH_LONG : TRUTH_EARLY;
    }

    static long stepsFor(Program p, Random rnd) {
        long n = randomSteps(rnd);
        return p == LONG_RUNNING ? 1 + n % LONG_RUNNING_MAX_STEPS : n;
    }

    static InMemoryGameChain chain(Program program) {
        return new InMemoryGameChain(program.hash(), 3600, PB, CB, 1_000_000);
    }

    // ----- honest challenger against lying proposers -----

    @Test
    @DisplayName("an honest challenger beats every lying-proposer strategy, 3000 random games, N up to 2^40")
    void honestChallengerAlwaysWins() {
        Random rnd = new Random(2026);
        int lies = 0;
        for (int game = 0; game < 3000; game++) {
            Program program = rnd.nextInt(4) == 0 ? HALTS_EARLY : LONG_RUNNING;
            Trace truth = truthFor(program);
            long steps = stepsFor(program, rnd);
            ClaimFunction claims = randomLiar(truth, steps, rnd);
            boolean claimIsFalse = !claims.at(steps).equals(truth.hashAt(steps));

            GameChain chain = chain(program);
            ProposerBot proposer = new ProposerBot(PROPOSER, claims, truth);
            ChallengerBot challenger = ChallengerBot.honest(CHALLENGER, truth);
            Outcome out = GameRunner.play(chain, proposer, steps, List.of(proposer, challenger), BYSTANDER);

            if (claimIsFalse) {
                lies++;
                assertThat(out.proposerWon()).as("game %d, N=%d: a false claim must lose", game, steps).isFalse();
                assertThat(out.resolution()).isEqualTo(Resolution.ONE_STEP_PROOF);
                assertThat(out.moves()).as("game %d, N=%d", game, steps).isLessThanOrEqualTo(GameRunner.maxMovesForDisputedGame(steps));
                assertThat(chain.credits(CHALLENGER)).isEqualTo(PB.add(CB));
                assertThat(chain.credits(PROPOSER)).isEqualTo(BigInteger.ZERO);
                // the game pinpoints a transition that is really invalid: the state at `lo` is true, the one at `hi` is claimed false
                assertThat(out.last().loHash()).isEqualTo(truth.hashAt(out.last().lo()));
                assertThat(out.last().hiHash()).isNotEqualTo(truth.hashAt(out.last().hi()));
                assertThat(out.last().hi() - out.last().lo()).isEqualTo(1);
            } else {
                // a "lie" that happens to converge to the truth at the end is a true claim: nobody disputes it
                assertThat(out.proposerWon()).isTrue();
                assertThat(out.resolution()).isEqualTo(Resolution.UNCHALLENGED);
            }
        }
        assertThat(lies).as("the generator produces real lies").isGreaterThan(2500);
    }

    @Test
    @DisplayName("an honest proposer is never beaten: it wins against a challenger that disputes everything")
    void honestProposerAlwaysWins() {
        Random rnd = new Random(99);
        for (Mode mode : List.of(Mode.ALWAYS_DISAGREE, Mode.ALWAYS_AGREE, Mode.RANDOM, Mode.WRONG_PRE_STATE, Mode.SILENT_AFTER_CHALLENGE)) {
            for (int game = 0; game < 300; game++) {
                Program program = rnd.nextBoolean() ? HALTS_EARLY : LONG_RUNNING;
                Trace truth = truthFor(program);
                long steps = stepsFor(program, rnd);

                GameChain chain = chain(program);
                ProposerBot proposer = new ProposerBot(PROPOSER, Claims.honest(truth), truth);
                ChallengerBot challenger = new ChallengerBot(CHALLENGER, truth, mode, rnd.nextLong());
                // the challenger gets the first chance to move, so WRONG_PRE_STATE really tries its forged proof
                Outcome out = GameRunner.play(chain, proposer, steps, List.of(challenger, proposer), BYSTANDER);

                assertThat(out.proposerWon()).as("%s, game %d, N=%d", mode, game, steps).isTrue();
                assertThat(chain.credits(PROPOSER)).isEqualTo(PB.add(CB));
                assertThat(chain.credits(CHALLENGER)).isEqualTo(BigInteger.ZERO);
                assertThat(out.moves()).isLessThanOrEqualTo(GameRunner.maxMovesForDisputedGame(steps) + 1);
            }
        }
    }

    @Test
    @DisplayName("a proposer that goes offline after a few bisections loses on the clock")
    void offlineProposerLosesByTimeout() {
        Trace truth = TRUTH_EARLY;
        long steps = 1L << 30;
        GameChain chain = chain(HALTS_EARLY);
        ProposerBot proposer = new ProposerBot(PROPOSER, Claims.honest(truth), truth, 3); // 3 bisections, then silence
        // an honest challenger would not challenge a true claim, so use one that disputes everything and answers honestly
        ChallengerBot challenger = new ChallengerBot(CHALLENGER, truth, Mode.ALWAYS_DISAGREE, 1);
        Outcome out = GameRunner.play(chain, proposer, steps, List.of(proposer, challenger), BYSTANDER);
        assertThat(out.proposerWon()).isFalse();
        assertThat(out.resolution()).isEqualTo(Resolution.TIMEOUT);
        assertThat(chain.credits(CHALLENGER)).isEqualTo(PB.add(CB));
    }

    @Test
    @DisplayName("nobody disputing a claim: it stands after the clock, and the bond comes back")
    void unchallengedClaimStands() {
        Trace truth = TRUTH_EARLY;
        GameChain chain = chain(HALTS_EARLY);
        ProposerBot proposer = new ProposerBot(PROPOSER, Claims.honest(truth), truth);
        ChallengerBot challenger = ChallengerBot.honest(CHALLENGER, truth); // finds nothing to dispute
        Outcome out = GameRunner.play(chain, proposer, 1000, List.of(proposer, challenger), BYSTANDER);
        assertThat(out.resolution()).isEqualTo(Resolution.UNCHALLENGED);
        assertThat(out.proposerWon()).isTrue();
        assertThat(chain.credits(PROPOSER)).isEqualTo(PB);
        assertThat(out.moves()).isEqualTo(2); // create, timeout
    }

    /** The limitation of a two-party game: a liar that controls the challenger seat makes its lie stand. */
    @Test
    @DisplayName("KNOWN LIMITATION: a lying proposer that challenges itself and lets itself time out wins (two-party game)")
    void sybilChallengerLetsALieStand() {
        Trace truth = TRUTH_LONG;
        GameChain chain = chain(LONG_RUNNING);
        long steps = 1000;
        ProposerBot liar = new ProposerBot(PROPOSER, Claims.wrongFrom(truth, 5, 1), truth);
        // the sybil takes the challenger seat in the same block the lie is posted, then never plays
        ChallengerBot sybil = new ChallengerBot(CHALLENGER, truth, Mode.SILENT_AFTER_CHALLENGE, 0);
        Outcome out = GameRunner.play(chain, liar, steps, List.of(liar, sybil), BYSTANDER);
        assertThat(out.proposerWon()).as("the false claim was validated").isTrue();
        // an honest challenger arriving afterwards finds the game already settled
        ChallengerBot honest = ChallengerBot.honest(BYSTANDER, truth);
        assertThat(honest.act(chain, out.gameId())).isFalse();
    }

    // ----- many games at once on one chain -----

    @Test
    @DisplayName("60 parallel games interleaved move by move: every one is decided correctly and bonds add up")
    void parallelGamesDoNotInterfere() {
        Random rnd = new Random(5);
        InMemoryGameChain chain = chain(LONG_RUNNING);
        Trace truth = TRUTH_LONG;

        record G(long id, ProposerBot proposer, ChallengerBot challenger, boolean lie) {}
        List<G> games = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long steps = 1 + rnd.nextInt(100_000);
            boolean lie = rnd.nextBoolean();
            ClaimFunction claims = lie ? Claims.wrongFrom(truth, 1 + (long) (rnd.nextDouble() * steps), i) : Claims.honest(truth);
            // distinct addresses per game so the credits can be told apart
            String p = String.format("0x%040x", 0x1000 + i);
            String c = String.format("0x%040x", 0x2000 + i);
            ProposerBot proposer = new ProposerBot(p, claims, truth);
            ChallengerBot challenger = new ChallengerBot(c, truth, lie ? Mode.HONEST : Mode.RANDOM, i);
            games.add(new G(proposer.open(chain, steps), proposer, challenger, lie));
        }
        for (int safety = 0; safety < 10_000; safety++) {
            List<G> open = games.stream().filter(g -> !chain.game(g.id()).resolved()).toList();
            if (open.isEmpty()) {
                break;
            }
            boolean anyMoved = false;
            for (G g : open) { // every open game gets a chance to move, interleaved with the others
                anyMoved |= g.challenger().act(chain, g.id()) || g.proposer().act(chain, g.id());
            }
            if (!anyMoved) { // nobody can or wants to move anywhere: the clocks run out
                chain.advanceTime(chain.moveTimeout() + 1);
                open.forEach(g -> chain.claimTimeout(BYSTANDER, g.id()));
            }
        }
        BigInteger total = BigInteger.ZERO;
        for (G g : games) {
            var v = chain.game(g.id());
            assertThat(v.resolved()).isTrue();
            boolean expectedProposerWin = !g.lie() || v.resolution() == Resolution.UNCHALLENGED;
            if (g.lie() && v.resolution() != Resolution.UNCHALLENGED) {
                assertThat(v.proposerWon()).as("a lie that was disputed must lose").isFalse();
            } else if (!g.lie()) {
                assertThat(v.proposerWon()).as("the truth must win").isTrue();
            }
            total = total.add(chain.credits(g.proposer().address())).add(chain.credits(g.challenger().address()));
            assertThat(expectedProposerWin || !v.proposerWon()).isTrue();
        }
        // every bond posted came back to somebody: 60 proposer bonds + one challenger bond per challenged game
        long challenged = games.stream().filter(g -> !chain.game(g.id()).challenger().equals(dev.rollupbridge.dispute.game.GameView.NO_ADDRESS)).count();
        assertThat(total).isEqualTo(PB.multiply(BigInteger.valueOf(60)).add(CB.multiply(BigInteger.valueOf(challenged))));
    }

    // ----- helpers -----

    /** Mix of tiny, mid and astronomically large N (up to 2^40), including exact powers of two and their neighbours. */
    static long randomSteps(Random rnd) {
        return switch (rnd.nextInt(5)) {
            case 0 -> 1 + rnd.nextInt(4);
            case 1 -> 1 + rnd.nextInt(10_000);
            case 2 -> 1L << (1 + rnd.nextInt(40));
            case 3 -> (1L << (2 + rnd.nextInt(38))) + rnd.nextInt(3) - 1;
            default -> 1 + (long) (rnd.nextDouble() * (1L << 40));
        };
    }

    /** One of the lying strategies, chosen at random. */
    static ClaimFunction randomLiar(Trace truth, long steps, Random rnd) {
        return switch (rnd.nextInt(5)) {
            case 0 -> Claims.wrongFrom(truth, steps, rnd.nextLong()); // only the final state is wrong
            case 1 -> Claims.wrongFrom(truth, 1 + (long) (rnd.nextDouble() * steps), rnd.nextLong());
            case 2 -> Claims.consistentLieFrom(truth, 1 + (long) (rnd.nextDouble() * steps));
            case 3 -> Claims.garbage(rnd.nextLong());
            default -> Claims.randomLie(truth, steps, rnd);
        };
    }
}
