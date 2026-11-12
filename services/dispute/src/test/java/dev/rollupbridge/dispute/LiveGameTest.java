package dev.rollupbridge.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.dispute.bot.ChallengerBot;
import dev.rollupbridge.dispute.bot.ChallengerBot.Mode;
import dev.rollupbridge.dispute.bot.Claims;
import dev.rollupbridge.dispute.bot.Claims.ClaimFunction;
import dev.rollupbridge.dispute.bot.GameRunner;
import dev.rollupbridge.dispute.bot.GameRunner.Outcome;
import dev.rollupbridge.dispute.bot.ProposerBot;
import dev.rollupbridge.dispute.evm.EvmGameChain;
import dev.rollupbridge.dispute.game.GameException;
import dev.rollupbridge.dispute.game.GameView;
import dev.rollupbridge.dispute.game.GameView.Resolution;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.Trace;
import dev.rollupbridge.dispute.vm.VmState;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.Anvil;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Full games played by the Java bots against the DEPLOYED {@code BisectionGame} on a live Anvil node: real
 * transactions, real reverts, real clocks (fast-forwarded with {@code evm_increaseTime}).
 */
class LiveGameTest {
    private static final String P = Accounts.PROPOSER.address();
    private static final String C = Accounts.CHALLENGER.address();
    private static final String B = Accounts.ALICE.address();
    private static final Program PROGRAM = Program.sumLoop(1L << 40); // runs for ~3 * 2^40 steps
    private static final BigInteger BOTH = Live.PROPOSER_BOND.add(Live.CHALLENGER_BOND);

    private static Anvil anvil;
    private static Trace truth;

    @BeforeAll
    static void start() {
        anvil = Live.anvil();
        truth = new Trace(PROGRAM, VmState.zero());
    }

    private static EvmGameChain freshGame() {
        return Live.deployGame(anvil, PROGRAM);
    }

    @Test
    @DisplayName("an honest challenger beats each lying proposer on the real contract (N = 2^20 steps, about 43 transactions each)")
    void honestChallengerBeatsLyingProposersOnChain() {
        long steps = 1L << 20;
        List<ClaimFunction> liars = List.of(
                Claims.wrongFrom(truth, steps, 11), // lies only about the final state
                Claims.wrongFrom(truth, 1, 12), // lies about everything
                Claims.consistentLieFrom(truth, 777_777), // one corrupted state, a valid execution afterwards
                Claims.garbage(13));
        for (ClaimFunction lie : liars) {
            EvmGameChain chain = freshGame();
            ProposerBot proposer = new ProposerBot(P, lie, truth);
            ChallengerBot challenger = ChallengerBot.honest(C, truth);
            Outcome out = GameRunner.play(chain, proposer, steps, List.of(proposer, challenger), B);

            assertThat(out.proposerWon()).isFalse();
            assertThat(out.resolution()).isEqualTo(Resolution.ONE_STEP_PROOF);
            assertThat(out.moves()).isEqualTo(2L * 20 + 3); // O(log N): 43 on-chain calls for a million steps
            assertThat(chain.credits(C)).isEqualTo(BOTH);
            assertThat(chain.credits(P)).isEqualTo(BigInteger.ZERO);

            BigInteger paid = chain.withdraw(C);
            assertThat(paid).isEqualTo(BOTH);
            assertThat(chain.credits(C)).isEqualTo(BigInteger.ZERO);
        }
    }

    @Test
    @DisplayName("an honest proposer beats every kind of lying challenger on the real contract, including a forged pre-state")
    void honestProposerBeatsLyingChallengersOnChain() {
        long steps = 1L << 12;
        for (Mode mode : List.of(Mode.ALWAYS_DISAGREE, Mode.ALWAYS_AGREE, Mode.RANDOM, Mode.WRONG_PRE_STATE, Mode.SILENT_AFTER_CHALLENGE)) {
            EvmGameChain chain = freshGame();
            ProposerBot proposer = new ProposerBot(P, Claims.honest(truth), truth);
            ChallengerBot challenger = new ChallengerBot(C, truth, mode, 42);
            Outcome out = GameRunner.play(chain, proposer, steps, List.of(challenger, proposer), B);

            assertThat(out.proposerWon()).as(mode.toString()).isTrue();
            assertThat(chain.credits(P)).isEqualTo(BOTH);
            assertThat(chain.credits(C)).isEqualTo(BigInteger.ZERO);
        }
    }

    @Test
    @DisplayName("the contract itself refuses a forged one-step proof (the revert surfaces as PreStateMismatch)")
    void forgedProofIsRefusedByTheContract() {
        EvmGameChain chain = freshGame();
        ProposerBot liar = new ProposerBot(P, Claims.wrongFrom(truth, 1, 1), truth);
        long id = liar.open(chain, 1); // one step: straight to the one-step proof once challenged
        chain.challenge(C, id);
        assertThat(chain.game(id).phase()).isEqualTo(GameView.Phase.AWAITING_STEP);

        VmState forged = truth.at(0).withReg(2, 5);
        assertThatThrownBy(() -> chain.step(C, id, PROGRAM, forged))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.error()).isEqualTo("PreStateMismatch"));
        assertThatThrownBy(() -> chain.step(C, id, Program.sumLoop(3), truth.at(0)))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.error()).isEqualTo("ProgramMismatch"));
        chain.step(B, id, PROGRAM, truth.at(0)); // anybody can submit the proof
        assertThat(chain.game(id).proposerWon()).isFalse();
    }

    @Test
    @DisplayName("clocks on a real chain: an unchallenged claim stands, a silent proposer loses, nobody can claim early")
    void clocksOnChain() {
        EvmGameChain chain = freshGame();
        ProposerBot proposer = new ProposerBot(P, Claims.honest(truth), truth, 1);
        long id = proposer.open(chain, 100);
        assertThatThrownBy(() -> chain.claimTimeout(B, id))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.error()).isEqualTo("TimeoutNotReached"));
        chain.advanceTime(Live.MOVE_TIMEOUT + 1);
        assertThatThrownBy(() -> chain.challenge(C, id))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.error()).isEqualTo("MoveTimeExpired"));
        chain.claimTimeout(B, id);
        assertThat(chain.game(id).resolution()).isEqualTo(Resolution.UNCHALLENGED);
        assertThat(chain.credits(P)).isEqualTo(Live.PROPOSER_BOND);
        assertThatThrownBy(() -> chain.claimTimeout(B, id))
                .isInstanceOfSatisfying(GameException.class, e -> assertThat(e.error()).isEqualTo("WrongPhase"));

        // a proposer that never bisects loses on the clock
        long id2 = proposer.open(chain, 100);
        chain.challenge(C, id2);
        chain.advanceTime(Live.MOVE_TIMEOUT + 1);
        chain.claimTimeout(B, id2);
        assertThat(chain.game(id2).proposerWon()).isFalse();
        assertThat(chain.game(id2).resolution()).isEqualTo(Resolution.TIMEOUT);
    }

    @Test
    @DisplayName("parallel games on one contract: the claims of two proposers are decided independently")
    void parallelGamesOnChain() {
        EvmGameChain chain = freshGame();
        ProposerBot honest = new ProposerBot(P, Claims.honest(truth), truth);
        ProposerBot liar = new ProposerBot(Accounts.MALLORY.address(), Claims.wrongFrom(truth, 500, 3), truth);
        ChallengerBot challenger = ChallengerBot.honest(C, truth);
        long a = liar.open(chain, 1000);
        long b = honest.open(chain, 1000);
        assertThat(chain.gameCount()).isEqualTo(2);
        challenger.act(chain, a); // the honest challenger disputes the lie only
        assertThat(challenger.act(chain, b)).isFalse(); // and finds nothing to dispute in the truthful claim
        for (int i = 0; i < 60 && !chain.game(a).resolved(); i++) {
            liar.act(chain, a);
            challenger.act(chain, a);
        }
        assertThat(chain.game(a).proposerWon()).isFalse();
        assertThat(chain.game(b).resolved()).isFalse(); // game b is untouched by game a
        chain.advanceTime(Live.MOVE_TIMEOUT + 1);
        chain.claimTimeout(B, b);
        assertThat(chain.game(b).proposerWon()).isTrue();
        assertThat(chain.credits(Accounts.MALLORY.address())).isEqualTo(BigInteger.ZERO);
        assertThat(chain.credits(C)).isEqualTo(BOTH);
        assertThat(chain.credits(P)).isEqualTo(Live.PROPOSER_BOND);
    }
}
