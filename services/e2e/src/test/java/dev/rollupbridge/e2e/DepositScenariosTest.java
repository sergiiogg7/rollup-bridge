package dev.rollupbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.core.memory.InMemoryStore;
import dev.rollupbridge.core.state.DepositState;
import dev.rollupbridge.testkit.Accounts;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E1 (happy deposit) and E6 (L1 reorgs against the deposit relayer). */
class DepositScenariosTest extends E2eBase {

    @Test
    @DisplayName("E1: a deposit is minted on L2 only after k confirmations")
    void e1_happyDeposit() {
        var store = new InMemoryStore();
        var relayer = depositRelayer(store, 2);

        bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH.multiply(BigInteger.valueOf(5)));
        relayer.tick();
        assertThat(store.deposit(0).orElseThrow().state()).isInstanceOf(DepositState.Seen.class);
        assertThat(bridge.l2Balance(Accounts.ALICE.address())).isZero();

        bridge.l1Node.mine(2);
        relayer.tick();
        assertThat(store.deposit(0).orElseThrow().state()).isInstanceOf(DepositState.Done.class);
        assertThat(bridge.l2Balance(Accounts.ALICE.address())).isEqualTo(ETH.multiply(BigInteger.valueOf(5)));
        assertThat(bridge.l1TotalLocked()).isEqualTo(ETH.multiply(BigInteger.valueOf(5)));
        assertThat(bridge.l2TotalSupply()).isEqualTo(bridge.l1TotalLocked());
    }

    @Test
    @DisplayName("E1b: two relayers (or a relayer with a lost database) never mint a deposit twice")
    void e1b_duplicateRelayerIsIdempotent() {
        var first = depositRelayer(new InMemoryStore(), 0);
        var secondWithEmptyStore = depositRelayer(new InMemoryStore(), 0);

        bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH);
        first.tick();
        secondWithEmptyStore.tick();
        first.tick();

        assertThat(bridge.l2Balance(Accounts.ALICE.address())).isEqualTo(ETH);
        assertThat(bridge.l2TotalSupply()).isEqualTo(ETH);
    }

    @Test
    @DisplayName("E6 (unsafe): relaying at ZERO confirmations loses funds when the L1 block is reorged away")
    void e6_zeroConfirmationsMintsUnbackedFundsAfterReorg() {
        var store = new InMemoryStore();
        var relayer = depositRelayer(store, 0); // <- the mistake being demonstrated

        bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH.multiply(BigInteger.valueOf(5)));
        relayer.tick();
        assertThat(bridge.l2Balance(Accounts.ALICE.address())).isEqualTo(ETH.multiply(BigInteger.valueOf(5)));

        // The L1 block holding the deposit is replaced by an empty one: the deposit never happened.
        bridge.l1Node.reorg(1);
        relayer.tick();

        assertThat(bridge.l1TotalLocked()).as("nothing is locked on L1 any more").isZero();
        assertThat(bridge.l1Balance(bridge.l1Bridge)).isZero();
        assertThat(bridge.l2Balance(Accounts.ALICE.address()))
                .as("...but L2 still shows 5 bETH: an UNBACKED MINT, i.e. lost funds")
                .isEqualTo(ETH.multiply(BigInteger.valueOf(5)));
        assertThat(bridge.l2TotalSupply()).isGreaterThan(bridge.l1TotalLocked());
        // the L1 side refuses to pay what it does not hold: that is its last line of defence
        assertThat(store.deposit(0).orElseThrow().state()).isInstanceOf(DepositState.Done.class);
    }

    @Test
    @DisplayName("E6 (safe): with k confirmations the same reorg is harmless and the deposit is relayed once it is re-included")
    void e6_confirmationsMakeTheSameReorgHarmless() {
        var store = new InMemoryStore();
        var relayer = depositRelayer(store, 3);

        bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH.multiply(BigInteger.valueOf(5)));
        relayer.tick();
        bridge.l1Node.mine(1);
        relayer.tick();
        assertThat(bridge.l2Balance(Accounts.ALICE.address())).isZero(); // still waiting

        bridge.l1Node.reorg(2); // deposit block and the empty one after it vanish
        bridge.l1Node.mine(5);
        relayer.tick();
        assertThat(store.deposit(0).orElseThrow().state()).isInstanceOf(DepositState.Dropped.class);
        assertThat(bridge.l2TotalSupply()).as("nothing was minted for a deposit that never became final").isZero();

        // Alice deposits again; the id 0 is reused by the new chain, the relayer picks it up as a fresh deposit.
        bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH.multiply(BigInteger.valueOf(5)));
        relayer.tick();
        bridge.l1Node.mine(3);
        relayer.tick();
        assertThat(bridge.l2Balance(Accounts.ALICE.address())).isEqualTo(ETH.multiply(BigInteger.valueOf(5)));
        assertThat(bridge.l2TotalSupply()).isEqualTo(bridge.l1TotalLocked());
    }

    @Test
    @DisplayName("Crash between the L2 send and the store commit leaves no duplicate mint")
    void crashAfterSendBeforeCommitDoesNotDoubleMint() {
        var store = new InMemoryStore();
        bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH);
        depositRelayer(store, 0).tick();
        // Simulate the lost commit: the store forgets that the deposit was Done (back to Relaying).
        var rec = store.deposit(0).orElseThrow();
        store.saveDeposit(new dev.rollupbridge.core.state.DepositRecord(
                rec.id(), rec.to(), rec.amount(), rec.l1Block(), rec.l1BlockHash(), new DepositState.Relaying()));

        depositRelayer(store, 0).tick(); // "restart": asks L2 first, finds it processed, only fixes its own record

        assertThat(store.deposit(0).orElseThrow().state()).isInstanceOf(DepositState.Done.class);
        assertThat(bridge.l2TotalSupply()).isEqualTo(ETH);
    }
}
