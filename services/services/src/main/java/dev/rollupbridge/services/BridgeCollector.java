package dev.rollupbridge.services;

import dev.rollupbridge.core.metrics.MetricsRegistry;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.state.DepositState;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes the gauges that describe the bridge as a whole; called just before each scrape (pull model, like a
 * Prometheus collector). Every section fails on its own: an RPC outage shows up as
 * {@code bridge_collector_errors_total{section}} and as stale values, never as an exception in the scrape.
 *
 * <p>Any store may be {@code null}: a process that runs only the challenger has no deposit database.
 */
public final class BridgeCollector {
    private static final Logger LOG = LoggerFactory.getLogger(BridgeCollector.class);
    /** Same bound as the challenger: only the newest outputs can still be inside a window. */
    private static final long LOOKBACK_OUTPUTS = 64;

    private static final List<String> DEPOSIT_STATES = List.of("Seen", "Confirmed", "Relaying", "Done", "Dropped");
    private static final List<String> WITHDRAWAL_STATES = List.of("Initiated", "Included", "Proven", "Invalidated", "Finalized");

    private final L1Reader l1;
    private final L2Reader l2;
    private final BridgeStore depositStore;
    private final BridgeStore withdrawalStore;
    private final BridgeStore challengerStore;
    private final MetricsRegistry m;

    public BridgeCollector(
            L1Reader l1,
            L2Reader l2,
            BridgeStore depositStore,
            BridgeStore withdrawalStore,
            BridgeStore challengerStore,
            MetricsRegistry metrics) {
        this.l1 = l1;
        this.l2 = l2;
        this.depositStore = depositStore;
        this.withdrawalStore = withdrawalStore;
        this.challengerStore = challengerStore;
        this.m = metrics;
        metrics.counter("bridge_collector_errors_total", "Failures while refreshing a group of gauges.");
    }

    /** Refreshes every gauge. Safe to call from any thread, concurrently with the services. */
    public synchronized void collect() {
        long l1Head = -1;
        long l2Head = -1;
        try {
            l1Head = l1.headNumber();
            m.gauge("bridge_chain_head_block", "Latest block number of each chain.").set(l1Head, "chain", "l1");
        } catch (RuntimeException e) {
            failed("l1_head", e);
        }
        try {
            l2Head = l2.headNumber();
            m.gauge("bridge_chain_head_block", "").set(l2Head, "chain", "l2");
        } catch (RuntimeException e) {
            failed("l2_head", e);
        }
        if (depositStore != null) {
            guard("deposits", this::deposits);
            if (l1Head >= 0) {
                lag(depositStore, DepositRelayer.CURSOR, l1Head, "l1");
            }
        }
        if (withdrawalStore != null) {
            guard("withdrawals", this::withdrawals);
            if (l2Head >= 0) {
                lag(withdrawalStore, WithdrawalRelayer.CURSOR, l2Head, "l2");
            }
        }
        guard("outputs", this::outputs);
    }

    private void lag(BridgeStore store, String cursorName, long head, String chain) {
        OptionalLong cursor = store.cursor(cursorName);
        if (cursor.isPresent()) {
            m.gauge("bridge_cursor_lag_blocks", "Chain head minus the block up to which a service has scanned.")
                    .set(Math.max(0, head - cursor.getAsLong()), "chain", chain);
        }
    }

    private void deposits() {
        long[] counts = new long[DEPOSIT_STATES.size()];
        for (var d : depositStore.allDeposits()) {
            counts[DEPOSIT_STATES.indexOf(d.state().name())]++;
        }
        var byState = m.gauge("bridge_deposits_by_state", "Deposits known to the relayer, by state.");
        for (int i = 0; i < counts.length; i++) {
            byState.set(counts[i], "state", DEPOSIT_STATES.get(i));
        }
        long waiting = depositStore.activeDeposits().stream().filter(d -> d.state() instanceof DepositState.Seen).count();
        m.gauge("bridge_deposits_pending", "Deposits that are neither Done nor Dropped.")
                .set(counts[0] + counts[1] + counts[2]);
        m.gauge("bridge_l1_confirmations_waiting", "Deposits seen on L1 that are still waiting for enough confirmations.")
                .set(waiting);
    }

    private void withdrawals() {
        long[] counts = new long[WITHDRAWAL_STATES.size()];
        for (var w : withdrawalStore.allWithdrawals()) {
            counts[WITHDRAWAL_STATES.indexOf(w.state().name())]++;
        }
        var byState = m.gauge("bridge_withdrawals_by_state", "Withdrawals known to the relayer, by state.");
        for (int i = 0; i < counts.length; i++) {
            byState.set(counts[i], "state", WITHDRAWAL_STATES.get(i));
        }
    }

    private void outputs() {
        long n = l1.outputCount();
        long window = l1.challengeWindow();
        long now = l1.headTimestamp();
        long[] byStatus = new long[OutputStatus.values().length];
        long open = 0;
        long unchecked = 0;
        long minRemaining = Long.MAX_VALUE;
        long minUncheckedRemaining = Long.MAX_VALUE;
        for (long i = Math.max(0, n - LOOKBACK_OUTPUTS); i < n; i++) {
            OutputRecord o = l1.output(i);
            byStatus[o.status().ordinal()]++;
            long remaining = o.proposedAt() + window - now;
            if (o.status() == OutputStatus.PROPOSED && remaining > 0) {
                open++;
                minRemaining = Math.min(minRemaining, remaining);
                if (challengerStore != null && !challengerStore.isChecked(i, o.withdrawalRoot())) {
                    unchecked++;
                    minUncheckedRemaining = Math.min(minUncheckedRemaining, remaining);
                }
            }
        }
        m.gauge("bridge_outputs_on_l1", "Number of outputs ever proposed on L1 (OutputOracle.outputCount).").set(n);
        var byStatusGauge = m.gauge("bridge_outputs", "Newest outputs on L1 by status.");
        for (OutputStatus s : OutputStatus.values()) {
            byStatusGauge.set(byStatus[s.ordinal()], "status", s.name().toLowerCase());
        }
        m.gauge("bridge_outputs_in_window", "Proposed outputs whose challenge window is still open.").set(open);
        var remainingGauge = m.gauge(
                "bridge_open_output_min_window_remaining_seconds",
                "Seconds left in the challenge window of the output closest to finalizing (absent when none is open).");
        if (open > 0) {
            remainingGauge.set(minRemaining);
        } else {
            remainingGauge.remove();
        }
        m.gauge("bridge_challenge_window_seconds", "Challenge window configured on the OutputOracle.").set(window);
        if (challengerStore != null) {
            m.gauge("bridge_challenger_unchecked_outputs",
                            "Open outputs this challenger has not verified yet.")
                    .set(unchecked);
            var uncheckedRemaining = m.gauge(
                    "bridge_unchecked_output_min_window_remaining_seconds",
                    "Seconds left in the challenge window of the open output the challenger has NOT verified that is closest to finalizing (absent when none).");
            if (unchecked > 0) {
                uncheckedRemaining.set(minUncheckedRemaining);
            } else {
                uncheckedRemaining.remove();
            }
        }
        m.gauge("bridge_l1_paused", "1 if the L1 bridge is paused.").set(l1.isPaused() ? 1 : 0);
    }

    private void guard(String section, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            failed(section, e);
        }
    }

    private void failed(String section, RuntimeException e) {
        LOG.warn("[collector] section {} failed: {}", section, e.getMessage());
        m.counter("bridge_collector_errors_total", "").inc("section", section);
    }
}
