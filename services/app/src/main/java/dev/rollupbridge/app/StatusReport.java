package dev.rollupbridge.app;

import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.WithdrawalRecord;
import java.util.List;

/** Human readable snapshot of the whole bridge: chain heads, outputs, and the relayers' view of every transfer. */
public final class StatusReport {
    private StatusReport() {}

    public static String render(
            L1Reader l1, L2Reader l2, BridgeStore depositStore, BridgeStore withdrawalStore) {
        StringBuilder sb = new StringBuilder();
        sb.append("rollup-bridge status\n");
        sb.append("  L1 head: ").append(l1.headNumber()).append("   L2 head: ").append(l2.headNumber())
                .append("   L1 paused: ").append(l1.isPaused()).append('\n');

        long n = l1.outputCount();
        sb.append("\nOutputs on L1 (").append(n).append(")\n");
        sb.append(String.format("  %-4s %-11s %-9s %-11s %-12s %s%n", "#", "status", "l2 block", "withdrawals", "finalizable", "root"));
        for (long i = 0; i < n; i++) {
            OutputRecord o = l1.output(i);
            sb.append(String.format("  %-4d %-11s %-9d %-11d %-12s %s%n", i, o.status(), o.l2BlockNumber(), o.withdrawalCount(),
                    l1.isFinalizable(i), shorten(o.withdrawalRoot().hex())));
        }

        List<DepositRecord> deposits = depositStore.allDeposits();
        sb.append("\nDeposits (L1 -> L2) as seen by the deposit relayer (").append(deposits.size()).append(")\n");
        for (DepositRecord d : deposits) {
            sb.append(String.format("  #%-3d %s wei -> %s  L1 block %d  %s%n", d.id(), d.amount(), shorten(d.to()), d.l1Block(), d.state()));
        }

        List<WithdrawalRecord> withdrawals = withdrawalStore.allWithdrawals();
        sb.append("\nWithdrawals (L2 -> L1) as seen by the withdrawal relayer (").append(withdrawals.size()).append(")\n");
        for (WithdrawalRecord w : withdrawals) {
            sb.append(String.format("  #%-3d %s wei -> %s  %s%n", w.withdrawal().id(), w.withdrawal().amount(),
                    shorten(w.withdrawal().target()), w.state()));
        }
        return sb.toString();
    }

    private static String shorten(String hex) {
        return hex.length() > 14 ? hex.substring(0, 8) + ".." + hex.substring(hex.length() - 4) : hex;
    }
}
