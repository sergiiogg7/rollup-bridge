package dev.rollupbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.memory.InMemoryStore;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.state.WithdrawalState;
import dev.rollupbridge.services.Challenger;
import dev.rollupbridge.services.DepositRelayer;
import dev.rollupbridge.services.Guardian;
import dev.rollupbridge.services.OutputVerifier;
import dev.rollupbridge.services.Proposer;
import dev.rollupbridge.services.WithdrawalRelayer;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.Faults;
import dev.rollupbridge.testkit.sim.SimWorld;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic regressions found by {@link ChaosPropertyTest}: each one is a script that the random run shrank to,
 * kept as a named test. They run on the in-memory model (no Anvil): an L1 reorg removes the very transaction the
 * service has just seen mined, and the service must notice from the chain and redo its step.
 */
class ReorgResilienceTest {
    private static final BigInteger ETH = SimWorld.ETH;

    private SimWorld world;
    private InMemoryStore relayerStore;
    private InMemoryStore challengerStore;
    private WithdrawalRelayer relayer;
    private Challenger challenger;
    private Guardian guardian;
    private Proposer proposer;
    private DepositRelayer deposits;

    @BeforeEach
    void setUp() {
        world = new SimWorld();
        relayerStore = new InMemoryStore();
        challengerStore = new InMemoryStore();
        relayer = new WithdrawalRelayer(world.l1(null), world.l1(Accounts.RELAYER), world.l2(null), relayerStore);
        challenger = new Challenger(world.l1(null), world.l1(Accounts.CHALLENGER), new OutputVerifier(world.l2(null)), challengerStore);
        guardian = new Guardian(world.l1(null), world.l1(Accounts.GUARDIAN), new OutputVerifier(world.l2(null)));
        proposer = new Proposer(world.l1(null), world.l1(Accounts.PROPOSER), world.l2(null), new Proposer.Config(0));
        deposits = new DepositRelayer(
                world.l1(null), world.l2(null), world.l2(Accounts.RELAYER), new InMemoryStore(), new DepositRelayer.Config(2, 0, 20));
    }

    /** Alice deposits 5 ETH, it is minted, she burns 2 ETH towards Bob, an output covers it. */
    private void aliceWithdraws() {
        world.deposit(Accounts.ALICE, Accounts.ALICE, ETH.multiply(BigInteger.valueOf(5)));
        world.mineL1(3);
        deposits.tick();
        world.withdraw(Accounts.ALICE, Accounts.BOB, ETH.multiply(BigInteger.TWO));
        proposer.tick();
    }

    @Test
    void aChallengeRemovedByAReorgIsChallengedAgain() {
        aliceWithdraws();
        // the proposer lies: an output with a fabricated withdrawal
        world.mineL2(1);
        WithdrawalTree lying = world.l2TreeCopy();
        Withdrawal fake = new Withdrawal(lying.size(), Accounts.MALLORY.address(), Accounts.MALLORY.address(), ETH);
        lying.append(fake.leaf());
        long head = world.l2(null).headNumber();
        world.l1(Accounts.PROPOSER).proposeOutput(lying.root(), world.l2(null).blockHash(head).orElseThrow(), head, lying.size());
        int bad = world.l1OutputCount() - 1;

        challenger.tick();
        assertThat(world.l1OutputStatuses().get(bad)).isEqualTo(OutputStatus.CHALLENGED);

        world.reorgL1(1, SimWorld.Reorg.DROP); // the challenge transaction never happened
        assertThat(world.l1OutputStatuses().get(bad)).isEqualTo(OutputStatus.PROPOSED);

        challenger.tick(); // must NOT consider the output handled just because it once sent a challenge
        assertThat(world.l1OutputStatuses().get(bad)).isEqualTo(OutputStatus.CHALLENGED);
        guardian.tick();
        assertThat(world.l1OutputStatuses().get(bad)).isEqualTo(OutputStatus.DELETED);
    }

    @Test
    void aProofRemovedByAReorgIsProvenAgainAndThenFinalized() {
        aliceWithdraws();
        relayer.tick();
        assertThat(relayerStore.withdrawal(0).orElseThrow().state()).isInstanceOf(WithdrawalState.Proven.class);

        world.reorgL1(1, SimWorld.Reorg.DROP); // the prove transaction is gone
        world.warpL1(world.params().challengeWindow() + 1);
        relayer.tick(); // sees that the proof is missing...
        relayer.tick(); // ...proves again...
        world.warpL1(world.params().challengeWindow() + 1);
        relayer.tick(); // ...and finalizes

        assertThat(world.l1Paid(Accounts.BOB.address())).isEqualTo(ETH.multiply(BigInteger.TWO));
    }

    @Test
    void aFinalizeRemovedByAReorgIsFinalizedAgain() {
        aliceWithdraws();
        relayer.tick();
        world.warpL1(world.params().challengeWindow() + 1);
        relayer.tick();
        assertThat(world.l1Paid(Accounts.BOB.address())).isEqualTo(ETH.multiply(BigInteger.TWO));
        assertThat(relayerStore.withdrawal(0).orElseThrow().state()).isInstanceOf(WithdrawalState.Finalized.class);

        world.reorgL1(1, SimWorld.Reorg.DROP); // the finalize transaction is gone: Bob has not been paid after all
        assertThat(world.l1Paid(Accounts.BOB.address())).isZero();

        for (int i = 0; i < 4; i++) {
            relayer.tick();
        }
        assertThat(world.l1Paid(Accounts.BOB.address())).isEqualTo(ETH.multiply(BigInteger.TWO));
    }

    @Test
    void duplicatedAndShuffledLogsDoNotMakeAnHonestOutputLookWrong() {
        aliceWithdraws();
        world.withdraw(Accounts.ALICE, Accounts.BOB, ETH);
        proposer.tick();
        var noisy = new Faults.Control(Faults.Plan.none().withLogChaos(1.0, 1.0), 42);
        L2Reader l2 = Faults.wrap(L2Reader.class, world.l2(null), noisy);
        var verifier = new OutputVerifier(l2);
        for (long i = 0; i < world.l1OutputCount(); i++) {
            assertThat(verifier.verify(world.l1(null).output(i))).isInstanceOf(OutputVerifier.Verdict.Valid.class);
        }
        assertThat(noisy.injected()).isPositive();
    }

    @Test
    void aNodeThatWithholdsALogMakesTheCheckInconclusiveNotAccusing() {
        aliceWithdraws();
        world.withdraw(Accounts.ALICE, Accounts.BOB, ETH);
        proposer.tick();
        L2Reader real = world.l2(null);
        L2Reader withholding = new L2Reader() {
            @Override
            public long headNumber() {
                return real.headNumber();
            }

            @Override
            public Optional<Bytes32> blockHash(long n) {
                return real.blockHash(n);
            }

            @Override
            public List<WithdrawalEvent> withdrawalEvents(long from, long to) {
                return real.withdrawalEvents(from, to).stream().filter(e -> e.withdrawal().id() != 0).toList();
            }

            @Override
            public Bytes32 withdrawalRootAt(long b) {
                return real.withdrawalRootAt(b);
            }

            @Override
            public long withdrawalCountAt(long b) {
                return real.withdrawalCountAt(b);
            }

            @Override
            public boolean isDepositProcessed(long id) {
                return real.isDepositProcessed(id);
            }
        };
        var verifier = new OutputVerifier(withholding);
        var last = world.l1(null).output(world.l1OutputCount() - 1);
        assertThatThrownBy(() -> verifier.verify(last)).isInstanceOf(GatewayException.class).hasMessageContaining("inconclusive");
    }
}
