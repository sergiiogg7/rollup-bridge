package dev.rollupbridge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.DepositState;
import dev.rollupbridge.core.state.DepositTransitions;
import dev.rollupbridge.core.state.WithdrawalRecord;
import dev.rollupbridge.core.state.WithdrawalState;
import dev.rollupbridge.core.state.WithdrawalTransitions;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class StateMachineTest {
    private static final List<DepositState> DEPOSIT_STATES = List.of(
            new DepositState.Seen(),
            new DepositState.Confirmed(),
            new DepositState.Relaying(),
            new DepositState.Done("0x1"),
            new DepositState.Dropped());
    private static final List<WithdrawalState> WITHDRAWAL_STATES = List.of(
            new WithdrawalState.Initiated(),
            new WithdrawalState.Included(1),
            new WithdrawalState.Proven(1, "0x1"),
            new WithdrawalState.Finalized("0x2", true),
            new WithdrawalState.Invalidated(1));

    @Test
    void depositTransitionTableIsExactlyWhatTheDiagramSays() {
        Set<String> actual = new TreeSet<>();
        for (DepositState from : DEPOSIT_STATES) {
            for (DepositState to : DEPOSIT_STATES) {
                if (DepositTransitions.allowed(from, to)) {
                    actual.add(from.name() + "->" + to.name());
                }
            }
        }
        assertThat(actual)
                .containsExactlyInAnyOrder(
                        "Seen->Seen",
                        "Seen->Confirmed",
                        "Seen->Dropped",
                        "Confirmed->Relaying",
                        "Confirmed->Dropped",
                        "Confirmed->Done",
                        "Relaying->Done",
                        "Relaying->Confirmed",
                        "Dropped->Seen");
    }

    @Test
    void withdrawalTransitionTableIsExactlyWhatTheDiagramSays() {
        Set<String> actual = new TreeSet<>();
        for (WithdrawalState from : WITHDRAWAL_STATES) {
            for (WithdrawalState to : WITHDRAWAL_STATES) {
                if (WithdrawalTransitions.allowed(from, to)) {
                    actual.add(from.name() + "->" + to.name());
                }
            }
        }
        assertThat(actual)
                .containsExactlyInAnyOrder(
                        "Initiated->Included",
                        "Initiated->Finalized",
                        "Included->Proven",
                        "Included->Finalized",
                        "Included->Invalidated",
                        "Proven->Finalized",
                        "Proven->Invalidated",
                        "Invalidated->Finalized",
                        "Invalidated->Included",
                        "Finalized->Invalidated");
    }

    @Test
    void terminalDepositsHaveNoExitAndFinalizedWithdrawalsOnlyTheReorgExit() {
        assertThat(DepositTransitions.table().get(DepositState.Done.class)).isEmpty();
        // a finalized withdrawal can only go back if an L1 reorg removed its finalize transaction
        assertThat(WithdrawalTransitions.table().get(WithdrawalState.Finalized.class)).containsExactly(WithdrawalState.Invalidated.class);
    }

    @Test
    void recordsEnforceTheTable() {
        String addr = "0x" + "11".repeat(20);
        DepositRecord d = new DepositRecord(0, addr, BigInteger.ONE, 5, Bytes32.ZERO, new DepositState.Seen());
        DepositRecord relaying = d.transitionTo(new DepositState.Confirmed()).transitionTo(new DepositState.Relaying());
        assertThat(relaying.state()).isInstanceOf(DepositState.Relaying.class);
        assertThatThrownBy(() -> d.transitionTo(new DepositState.Done("x"))).isInstanceOf(IllegalStateException.class);

        DepositRecord dropped = d.transitionTo(new DepositState.Dropped());
        DepositRecord again = dropped.reobserved(9, Bytes32.ZERO, addr, BigInteger.TWO);
        assertThat(again.state()).isInstanceOf(DepositState.Seen.class);
        assertThat(again.amount()).isEqualTo(BigInteger.TWO);
        assertThat(again.l1Block()).isEqualTo(9);

        Withdrawal w = new Withdrawal(0, addr, "0x" + "22".repeat(20), BigInteger.ONE);
        WithdrawalRecord r = new WithdrawalRecord(w, 3, new WithdrawalState.Initiated());
        assertThatThrownBy(() -> r.transitionTo(new WithdrawalState.Proven(0, "0x")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(r.transitionTo(new WithdrawalState.Included(0)).state()).isEqualTo(new WithdrawalState.Included(0));
    }
}
