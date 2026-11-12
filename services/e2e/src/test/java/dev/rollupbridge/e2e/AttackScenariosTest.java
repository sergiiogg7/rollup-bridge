package dev.rollupbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.adapters.rpc.Signer;
import dev.rollupbridge.adapters.rpc.TxSender;
import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.memory.InMemoryStore;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.state.WithdrawalState;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.MaliciousProposer;
import java.math.BigInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** E3, E4 and the "what if nobody watches" cases: attacks on the optimistic part of the bridge. */
class AttackScenariosTest extends E2eBase {
    private static final BigInteger FIVE = ETH.multiply(BigInteger.valueOf(5));
    private static final BigInteger FOUR = ETH.multiply(BigInteger.valueOf(4));

    private MaliciousProposer malicious;

    @BeforeEach
    void honestUsersAndAnAttacker() {
        fundAliceOnL2(FIVE, new InMemoryStore());
        // Alice makes one real withdrawal so the attacker's lie has to extend a non-empty tree
        bridge.withdraw(Accounts.ALICE, Accounts.ALICE.address(), ETH);
        malicious = new MaliciousProposer(l2Read(), bridge.l1(Accounts.PROPOSER));
    }

    @Test
    @DisplayName("E3: fake leaf -> challenger challenges -> guardian rules -> output deleted -> the theft never pays")
    void e3_fakeLeafIsCaughtAndNeverPays() {
        var challenger = challenger(new InMemoryStore());
        BigInteger challengerBefore = bridge.oracleCredits(Accounts.CHALLENGER.address());

        var posted = malicious.propose(MaliciousProposer.Mode.FAKE_LEAF, Accounts.MALLORY.address(), FOUR);
        assertThat(posted.tx().success()).isTrue();

        // While merely "Proposed" the forged withdrawal even passes the proof check: that is what the window is for.
        bridge.l1(Accounts.MALLORY).proveWithdrawal(posted.fakeWithdrawal(), 0, posted.fakeProof());

        challenger.tick(); // re-derives the tree from L2 events, sees the extra leaf
        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.CHALLENGED);

        // Nothing can finalize while the challenge is open, however long we wait.
        passChallengeWindow();
        assertThatThrownBy(() -> bridge.l1(Accounts.MALLORY).finalizeWithdrawal(posted.fakeWithdrawal()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("OutputNotFinalizable");

        guardian().tick(); // independent re-check, rules against the proposer
        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.DELETED);
        assertThat(bridge.oracleCredits(Accounts.CHALLENGER.address()).subtract(challengerBefore))
                .as("challenger receives its own bond plus the proposer bond")
                .isEqualTo(bridge.params.proposerBond().add(bridge.params.challengerBond()));

        bridge.l1Node.increaseTime(100_000);
        BigInteger malloryBefore = bridge.l1Balance(Accounts.MALLORY.address());
        assertThatThrownBy(() -> bridge.l1(Accounts.MALLORY).finalizeWithdrawal(posted.fakeWithdrawal()))
                .isInstanceOf(GatewayException.class);
        assertThat(bridge.l1Balance(Accounts.MALLORY.address())).isEqualTo(malloryBefore);
        assertThat(bridge.l1TotalLocked()).as("depositors funds untouched").isEqualTo(FIVE);
    }

    @ParameterizedTest
    @EnumSource(MaliciousProposer.Mode.class)
    @DisplayName("E3b: every kind of lie (fake leaf, wrong count, wrong block hash, foreign root) is challenged")
    void e3b_everyKindOfLieIsChallenged(MaliciousProposer.Mode mode) {
        malicious.propose(mode, Accounts.MALLORY.address(), FOUR);
        challenger(new InMemoryStore()).tick();
        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.CHALLENGED);
        guardian().tick();
        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.DELETED);
    }

    @Test
    @DisplayName("E3c: an honest output is verified and left alone")
    void e3c_honestOutputIsNotChallenged() {
        proposer().tick();
        challenger(new InMemoryStore()).tick();
        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.PROPOSED);
    }

    @Test
    @DisplayName("E3d: after the fake output is deleted, the honest proposer re-proposes and the real withdrawal pays")
    void e3d_recoveryAfterDeletion() {
        var store = new InMemoryStore();
        var relayer = withdrawalRelayer(store);
        malicious.propose(MaliciousProposer.Mode.FAKE_LEAF, Accounts.MALLORY.address(), FOUR);
        challenger(new InMemoryStore()).tick();
        guardian().tick();

        relayer.tick();
        assertThat(store.withdrawal(0).orElseThrow().state()).as("no live output yet").isInstanceOf(WithdrawalState.Initiated.class);

        proposer().tick(); // output #1, the honest one
        relayer.tick();
        passChallengeWindow();
        BigInteger aliceBefore = bridge.l1Balance(Accounts.ALICE.address());
        relayer.tick();
        assertThat(store.withdrawal(0).orElseThrow().state()).isInstanceOf(WithdrawalState.Finalized.class);
        assertThat(bridge.l1Balance(Accounts.ALICE.address())).isEqualTo(aliceBefore.add(ETH));
    }

    @Test
    @DisplayName("E4: a malicious challenger challenges an honest output and loses its bond")
    void e4_maliciousChallengerLosesItsBond() {
        proposer().tick();
        bridge.l1(Accounts.CHALLENGER).challengeOutput(0); // griefing: the output is honest
        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.CHALLENGED);

        guardian().tick();

        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.PROPOSED);
        assertThat(bridge.oracleCredits(Accounts.PROPOSER.address()))
                .as("the proposer is compensated with the challenger's bond")
                .isEqualTo(bridge.params.challengerBond());
        assertThat(bridge.oracleCredits(Accounts.CHALLENGER.address())).isZero();

        // The output got a fresh window and finalizes normally afterwards.
        var store = new InMemoryStore();
        var relayer = withdrawalRelayer(store);
        relayer.tick();
        passChallengeWindow();
        relayer.tick();
        assertThat(store.withdrawal(0).orElseThrow().state()).isInstanceOf(WithdrawalState.Finalized.class);
    }

    @Test
    @DisplayName("NEGATIVE: if nobody challenges inside the window, the theft SUCCEEDS (the liveness assumption)")
    void challengerOfflineLongerThanTheWindowLetsTheAttackThrough() {
        var posted = malicious.propose(MaliciousProposer.Mode.FAKE_LEAF, Accounts.MALLORY.address(), FOUR);
        bridge.l1(Accounts.MALLORY).proveWithdrawal(posted.fakeWithdrawal(), 0, posted.fakeProof());

        passChallengeWindow(); // the challenger was offline for the whole window
        BigInteger before = bridge.l1Balance(Accounts.MALLORY.address());
        bridge.l1(Accounts.MALLORY).finalizeWithdrawal(posted.fakeWithdrawal());

        assertThat(bridge.l1Balance(Accounts.MALLORY.address()))
                .as("4 ETH stolen from depositors (minus the gas Mallory paid)")
                .isBetween(before.add(FOUR).subtract(ETH.divide(BigInteger.valueOf(100))), before.add(FOUR));
        assertThat(bridge.l1TotalLocked()).isEqualTo(FIVE.subtract(FOUR));
        // and a late challenge is rejected
        assertThatThrownBy(() -> bridge.l1(Accounts.CHALLENGER).challengeOutput(0))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("WindowClosed");
    }

    @Test
    @DisplayName("An unresponsive guardian cannot freeze the system: the challenge expires, the output is deleted, bonds are refunded")
    void unresponsiveGuardianChallengeExpires() {
        malicious.propose(MaliciousProposer.Mode.FAKE_LEAF, Accounts.MALLORY.address(), FOUR);
        challenger(new InMemoryStore()).tick();
        bridge.l1Node.increaseTime(bridge.params.resolutionPeriod() + 1); // the guardian never answered

        assertThatThrownBy(() -> bridge.l1(Accounts.GUARDIAN).resolveChallenge(0, true))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("ResolutionExpired");
        bridge.l1(Accounts.BOB).expireChallenge(0); // anyone can call it

        assertThat(l1Read().output(0).status()).isEqualTo(OutputStatus.DELETED);
        assertThat(bridge.oracleCredits(Accounts.CHALLENGER.address())).isEqualTo(bridge.params.challengerBond());
        assertThat(bridge.oracleCredits(Accounts.PROPOSER.address())).isEqualTo(bridge.params.proposerBond());
    }

    @Test
    @DisplayName("The owner can replace a compromised or unresponsive guardian")
    void ownerReplacesTheGuardian() {
        var owner = new TxSender(bridge.l1Node.rpc(), new Signer(Accounts.DEPLOYER.privateKey()), bridge.l1Node.chainId());
        owner.send(bridge.outputOracle, BigInteger.ZERO,
                Abi.encodeCall("setGuardian(address)", Abi.address(Accounts.BOB.address())));
        assertThatThrownBy(() -> bridge.l1(Accounts.GUARDIAN).pause())
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("NotGuardian");
        bridge.l1(Accounts.BOB).pause();
        assertThat(l1Read().isPaused()).isTrue();
    }
}
