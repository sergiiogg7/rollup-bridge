package dev.rollupbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.adapters.rpc.Signer;
import dev.rollupbridge.adapters.rpc.TxSender;
import dev.rollupbridge.adapters.store.SqliteStore;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.memory.InMemoryStore;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.state.WithdrawalState;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.FaultyL1Writer;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** E2, E5, E7, E8, E9, E10, E11: the honest withdrawal path and everything that can interrupt it. */
class WithdrawalScenariosTest extends E2eBase {
    private static final BigInteger FIVE = ETH.multiply(BigInteger.valueOf(5));
    private static final BigInteger TWO = ETH.multiply(BigInteger.TWO);

    private WithdrawalState stateOf(BridgeStore store, long id) {
        return store.withdrawal(id).orElseThrow().state();
    }

    /** Alice has 5 bETH on L2 and withdraws 2 ETH to Bob on L1. Returns the withdrawal id (0). */
    private long aliceWithdrawsTwoToBob(BridgeStore depositStore) {
        fundAliceOnL2(FIVE, depositStore);
        return bridge.withdraw(Accounts.ALICE, Accounts.BOB.address(), TWO).success() ? 0 : -1;
    }

    @Test
    @DisplayName("E2: full withdrawal L2 -> proposed -> proven -> window -> finalized -> ETH on L1")
    void e2_fullWithdrawal() {
        long id = aliceWithdrawsTwoToBob(new InMemoryStore());
        var store = new InMemoryStore();
        var relayer = withdrawalRelayer(store);
        BigInteger bobBefore = bridge.l1Balance(Accounts.BOB.address());

        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Initiated.class); // no output yet

        proposer().tick();
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Proven.class);

        relayer.tick();
        assertThat(stateOf(store, id)).as("window still open").isInstanceOf(WithdrawalState.Proven.class);
        assertThat(bridge.l1Balance(Accounts.BOB.address())).isEqualTo(bobBefore);

        passChallengeWindow();
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Finalized.class);
        assertThat(((WithdrawalState.Finalized) stateOf(store, id)).paid()).isTrue();
        assertThat(bridge.l1Balance(Accounts.BOB.address())).isEqualTo(bobBefore.add(TWO));
        assertThat(bridge.l1TotalLocked()).isEqualTo(FIVE.subtract(TWO));
        assertThat(bridge.l2TotalSupply()).isEqualTo(FIVE.subtract(TWO)); // conservation across both chains
    }

    @Test
    @DisplayName("E5: relayer crashes right after finalize was mined; the restarted relayer pays nobody twice")
    void e5_crashBetweenFinalizeAndStoreCommit(@TempDir Path dir) {
        long id = aliceWithdrawsTwoToBob(new InMemoryStore());
        proposer().tick();
        BigInteger bobBefore = bridge.l1Balance(Accounts.BOB.address());

        try (SqliteStore store = SqliteStore.file(dir.resolve("relayer.db"))) {
            var doomed = withdrawalRelayer(store, new FaultyL1Writer(bridge.l1(Accounts.RELAYER), "finalize"));
            doomed.tick(); // ingest + include + prove
            assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Proven.class);
            passChallengeWindow();
            assertThatThrownBy(doomed::tick).isInstanceOf(FaultyL1Writer.SimulatedCrash.class);
            assertThat(bridge.l1Balance(Accounts.BOB.address())).as("the tx WAS mined").isEqualTo(bobBefore.add(TWO));
            assertThat(stateOf(store, id)).as("...but the store never heard of it").isInstanceOf(WithdrawalState.Proven.class);
        }

        // "restart": a brand new process opens the same database file
        try (SqliteStore store = SqliteStore.file(dir.resolve("relayer.db"))) {
            assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Proven.class); // survived the kill
            withdrawalRelayer(store).tick();
            assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Finalized.class);
        }
        assertThat(bridge.l1Balance(Accounts.BOB.address())).isEqualTo(bobBefore.add(TWO)); // paid exactly once
    }

    @Test
    @DisplayName("E5b: relayer crashes right after prove was mined; the restarted relayer does not prove again")
    void e5b_crashBetweenProveAndStoreCommit(@TempDir Path dir) {
        long id = aliceWithdrawsTwoToBob(new InMemoryStore());
        proposer().tick();
        try (SqliteStore store = SqliteStore.file(dir.resolve("relayer.db"))) {
            var doomed = withdrawalRelayer(store, new FaultyL1Writer(bridge.l1(Accounts.RELAYER), "prove"));
            assertThatThrownBy(doomed::tick).isInstanceOf(FaultyL1Writer.SimulatedCrash.class);
            assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Included.class);
        }
        try (SqliteStore store = SqliteStore.file(dir.resolve("relayer.db"))) {
            withdrawalRelayer(store).tick();
            var state = (WithdrawalState.Proven) stateOf(store, id);
            assertThat(state.proveTx()).as("recorded from the chain, no second prove sent").isEqualTo("observed-on-chain");
            passChallengeWindow();
            withdrawalRelayer(store).tick();
            assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Finalized.class);
        }
    }

    @Test
    @DisplayName("E7: an L1 reorg removes the proposeOutput and prove transactions; everything is redone")
    void e7_reorgRemovesTheOutput() {
        long id = aliceWithdrawsTwoToBob(new InMemoryStore());
        var store = new InMemoryStore();
        var relayer = withdrawalRelayer(store);

        proposer().tick(); // block N: proposeOutput
        relayer.tick(); // block N+1: prove
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Proven.class);
        assertThat(l1Read().outputCount()).isEqualTo(1);

        bridge.l1Node.reorg(2); // both transactions vanish
        assertThat(l1Read().outputCount()).isZero();

        relayer.tick(); // finds out its output no longer exists
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Invalidated.class);
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Invalidated.class); // still no output

        proposer().tick(); // the stateless proposer notices and proposes again
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Proven.class);
        passChallengeWindow();
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Finalized.class);
    }

    @Test
    @DisplayName("E8: restarting the proposer does not post a duplicate output")
    void e8_proposerRestartIsIdempotent() {
        aliceWithdrawsTwoToBob(new InMemoryStore());
        proposer().tick();
        proposer().tick(); // "restart"
        assertThat(l1Read().outputCount()).isEqualTo(1);
        bridge.withdraw(Accounts.ALICE, Accounts.BOB.address(), ETH);
        proposer().tick();
        assertThat(l1Read().outputCount()).as("new withdrawal => new output").isEqualTo(2);
        assertThat(l1Read().output(1).withdrawalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("E9: output deleted by the guardian, honest re-proposal, re-prove, payout exactly once")
    void e9_reproveAfterDeletedOutput() {
        long id = aliceWithdrawsTwoToBob(new InMemoryStore());
        var store = new InMemoryStore();
        var relayer = withdrawalRelayer(store);
        proposer().tick();
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Proven.class);

        bridge.l1(Accounts.GUARDIAN).deleteFrom(0); // emergency deletion inside the window
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Invalidated.class);

        proposer().tick(); // honest re-proposal as output #1
        relayer.tick();
        var proven = (WithdrawalState.Proven) stateOf(store, id);
        assertThat(proven.outputIndex()).isEqualTo(1);

        BigInteger bobBefore = bridge.l1Balance(Accounts.BOB.address());
        passChallengeWindow();
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Finalized.class);
        assertThat(bridge.l1Balance(Accounts.BOB.address())).isEqualTo(bobBefore.add(TWO));
        relayer.tick();
        assertThat(bridge.l1Balance(Accounts.BOB.address())).as("no second payout").isEqualTo(bobBefore.add(TWO));
    }

    @Test
    @DisplayName("E10: while the bridge is paused nothing is proven or finalized; after unpause it completes")
    void e10_pause() {
        long id = aliceWithdrawsTwoToBob(new InMemoryStore());
        var store = new InMemoryStore();
        var relayer = withdrawalRelayer(store);
        proposer().tick();
        relayer.tick();
        passChallengeWindow();

        guardian().pause();
        relayer.tick();
        assertThat(stateOf(store, id)).as("paused: stays Proven").isInstanceOf(WithdrawalState.Proven.class);
        assertThatThrownBy(() -> bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("IsPaused");

        guardian().unpause();
        relayer.tick();
        assertThat(stateOf(store, id)).isInstanceOf(WithdrawalState.Finalized.class);
    }

    @Test
    @DisplayName("E11: a target that reverts on receive is credited to claimable and does not block the next withdrawal")
    void e11_revertingTarget() {
        fundAliceOnL2(FIVE, new InMemoryStore());
        String rejecting = deployRejectingContract();
        bridge.withdraw(Accounts.ALICE, rejecting, TWO); // id 0: will fail to pay
        bridge.withdraw(Accounts.ALICE, Accounts.BOB.address(), TWO); // id 1: must still pay

        var store = new InMemoryStore();
        var relayer = withdrawalRelayer(store);
        proposer().tick();
        relayer.tick();
        passChallengeWindow();
        BigInteger bobBefore = bridge.l1Balance(Accounts.BOB.address());
        relayer.tick();

        var bad = (WithdrawalState.Finalized) stateOf(store, 0);
        assertThat(bad.paid()).as("payout failed, credited instead").isFalse();
        assertThat(l1Read().claimable(rejecting)).isEqualTo(TWO);
        var good = (WithdrawalState.Finalized) stateOf(store, 1);
        assertThat(good.paid()).isTrue();
        assertThat(bridge.l1Balance(Accounts.BOB.address())).isEqualTo(bobBefore.add(TWO));
    }

    /** Deploys a contract whose runtime code is just REVERT, i.e. it rejects any ETH sent to it. */
    private String deployRejectingContract() {
        byte[] initCode = Hex.decode("0x600580600b6000396000f360006000fd");
        var sender = new TxSender(bridge.l1Node.rpc(), new Signer(Accounts.DEPLOYER.privateKey()), bridge.l1Node.chainId());
        return Hex.address(sender.send(null, BigInteger.ZERO, initCode).contractAddress());
    }
}
