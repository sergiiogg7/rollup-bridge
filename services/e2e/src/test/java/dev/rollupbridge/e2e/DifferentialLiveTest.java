package dev.rollupbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.testkit.Accounts;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Differential tests of the Java tree against the DEPLOYED Solidity contracts on a live Anvil chain. */
class DifferentialLiveTest extends E2eBase {

    @Test
    @DisplayName("Java root equals the L2Bridge root and event newRoot after every one of 40 random withdrawals")
    void javaRootEqualsContractRootForRandomWithdrawals() {
        Random rnd = new Random(42);
        var l2 = bridge.l2(Accounts.RELAYER);
        l2.finalizeDeposit(0, Accounts.ALICE.address(), ETH.multiply(BigInteger.valueOf(1000)));

        WithdrawalTree tree = new WithdrawalTree();
        for (int i = 0; i < 40; i++) {
            String target = "0x" + String.format("%040x", new BigInteger(160, rnd));
            BigInteger amount = BigInteger.valueOf(1 + rnd.nextInt(1_000_000)).multiply(BigInteger.valueOf(1_000_000_000L));
            bridge.withdraw(Accounts.ALICE, target, amount);
            tree.append(new Withdrawal(i, Accounts.ALICE.address(), target, amount).leaf());

            long head = l2Read().headNumber();
            assertThat(l2Read().withdrawalRootAt(head)).as("root after %d withdrawals", i + 1).isEqualTo(tree.root());
            assertThat(l2Read().withdrawalCountAt(head)).isEqualTo(i + 1);
        }

        List<WithdrawalEvent> events = l2Read().withdrawalEvents(0, l2Read().headNumber());
        assertThat(events).hasSize(40);
        for (WithdrawalEvent e : events) {
            assertThat(e.withdrawal().leaf()).as("leaf of #%d", e.withdrawal().id()).isEqualTo(e.leaf());
            assertThat(tree.rootAt((int) e.withdrawal().id() + 1)).isEqualTo(e.newRoot());
        }
    }

    @Test
    @DisplayName("Proofs built in Java are accepted by the deployed L1Bridge for every leaf of a 13-leaf tree")
    void javaProofsVerifyInTheContract() {
        bridge.l2(Accounts.RELAYER).finalizeDeposit(0, Accounts.ALICE.address(), ETH.multiply(BigInteger.valueOf(100)));
        bridge.deposit(Accounts.ALICE, Accounts.ALICE, ETH.multiply(BigInteger.valueOf(50))); // L1 liquidity
        for (int i = 0; i < 13; i++) {
            bridge.withdraw(Accounts.ALICE, Accounts.BOB.address(), BigInteger.valueOf(1000 + i));
        }
        proposer().tick();
        var out = l1Read().output(0);
        WithdrawalTree tree = new WithdrawalTree();
        for (WithdrawalEvent e : l2Read().withdrawalEvents(0, out.l2BlockNumber())) {
            tree.append(e.withdrawal().leaf());
        }
        assertThat(tree.rootAt(13)).isEqualTo(out.withdrawalRoot());
        for (WithdrawalEvent e : l2Read().withdrawalEvents(0, out.l2BlockNumber())) {
            int id = (int) e.withdrawal().id();
            bridge.l1(Accounts.RELAYER).proveWithdrawal(e.withdrawal(), 0, tree.proof(id, 13)); // reverts if invalid
            assertThat(l1Read().proof(e.withdrawal().leaf()).exists()).isTrue();
        }
    }
}
