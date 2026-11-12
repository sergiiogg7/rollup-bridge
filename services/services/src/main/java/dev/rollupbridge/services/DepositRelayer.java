package dev.rollupbridge.services;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.metrics.MetricsRegistry;
import dev.rollupbridge.core.model.DepositEvent;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.port.L2Writer;
import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.DepositState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors L1 deposits to L2.
 *
 * <p>Two rules make it safe against L1 reorgs (this is the whole point of the E6 scenarios):
 *
 * <ol>
 *   <li><b>Confirmations.</b> A deposit is relayed only when its block is at least {@code confirmations} blocks
 *       below the head. With {@code confirmations = 0} the relayer mints on L2 as soon as it sees the event:
 *       if that L1 block is then reorged away, the L2 mint has no backing. That configuration exists only as a
 *       documented demonstration.
 *   <li><b>Canonical re-check.</b> Right before sending, the relayer asks the node for the block hash at the
 *       deposit height and compares it with the one it recorded.
 * </ol>
 *
 * <p>Idempotency (analogy: a Kafka consumer with offsets): progress lives in the store; the persisted
 * {@code Relaying} marker is written BEFORE the L2 transaction, and on restart the relayer asks L2 whether the
 * deposit id was already processed before it sends anything. The L2 replay guard is the last line of defence.
 */
public final class DepositRelayer implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(DepositRelayer.class);
    /** Name of the persisted scan cursor (also read by the metrics collector to compute the lag). */
    public static final String CURSOR = "deposit.scannedUpTo";

    /** @param confirmations required depth (head - block); @param startBlock first L1 block to scan; @param rescanDepth blocks re-read each tick to notice reorgs */
    public record Config(long confirmations, long startBlock, long rescanDepth) {}

    private final L1Reader l1;
    private final L2Reader l2;
    private final L2Writer l2Writer;
    private final BridgeStore store;
    private final Config config;

    private final MetricsRegistry metrics;
    /** First time THIS process saw a deposit still in flight; used for latency and age (lost on restart, by design). */
    private final Map<Long, Instant> firstSeen = new HashMap<>();

    public DepositRelayer(L1Reader l1, L2Reader l2, L2Writer l2Writer, BridgeStore store, Config config) {
        this(l1, l2, l2Writer, store, config, new MetricsRegistry());
    }

    public DepositRelayer(
            L1Reader l1, L2Reader l2, L2Writer l2Writer, BridgeStore store, Config config, MetricsRegistry metrics) {
        this.l1 = l1;
        this.l2 = l2;
        this.l2Writer = l2Writer;
        this.store = store;
        this.config = config;
        this.metrics = metrics;
        metrics.histogram(
                "bridge_deposit_latency_seconds",
                "Seconds from the relayer first seeing a deposit to its mint on L2 (deposits seen by this process only).",
                1, 2, 5, 10, 30, 60, 120, 300, 600);
        metrics.counter("bridge_deposit_reorged_after_mint_total", "Deposits minted on L2 that later appeared in a different L1 block (unbacked mint suspected).").init();
        metrics.gauge(
                "bridge_deposit_oldest_pending_age_seconds",
                "Age of the oldest deposit that is not Done or Dropped (0 when none), measured from first sight by this process.");
    }

    @Override
    public String name() {
        return "deposit-relayer";
    }

    @Override
    public void tick() {
        long head = l1.headNumber();
        scan(head);
        for (DepositRecord rec : store.activeDeposits()) {
            advance(rec, head);
        }
        observe();
    }

    /** Publishes latency (on completion) and the age of the oldest pending deposit. */
    private void observe() {
        Instant now = metrics.clock().instant();
        Instant oldest = null;
        for (DepositRecord rec : store.activeDeposits()) {
            Instant seen = firstSeen.computeIfAbsent(rec.id(), id -> now);
            if (oldest == null || seen.isBefore(oldest)) {
                oldest = seen;
            }
        }
        metrics.gauge("bridge_deposit_oldest_pending_age_seconds", "")
                .set(oldest == null ? 0 : Duration.between(oldest, now).toMillis() / 1000.0);
        var finished = new ArrayList<Long>();
        for (var entry : firstSeen.entrySet()) {
            Optional<DepositRecord> rec = store.deposit(entry.getKey());
            if (rec.isPresent() && rec.get().state() instanceof DepositState.Done) {
                metrics.histogram("bridge_deposit_latency_seconds", "")
                        .observe(Duration.between(entry.getValue(), now).toMillis() / 1000.0);
                finished.add(entry.getKey());
            } else if (rec.isEmpty() || rec.get().state() instanceof DepositState.Dropped) {
                finished.add(entry.getKey());
            }
        }
        finished.forEach(firstSeen::remove);
    }

    /** Reads recent logs (re-reading a window behind the cursor) and reconciles them with the store. */
    private void scan(long head) {
        long cursor = store.cursor(CURSOR).orElse(config.startBlock() - 1);
        long from = Math.max(config.startBlock(), cursor - config.rescanDepth() + 1);
        List<DepositEvent> events = l1.depositEvents(from, head);
        Set<Long> seenNow = new HashSet<>();
        for (DepositEvent e : events) {
            seenNow.add(e.depositId());
            Optional<DepositRecord> existing = store.deposit(e.depositId());
            if (existing.isEmpty()) {
                LOG.info("[deposit-relayer] new deposit #{} {} wei -> {} in L1 block {}", e.depositId(), e.amount(), e.to(), e.l1Block());
                firstSeen.putIfAbsent(e.depositId(), metrics.clock().instant());
                store.saveDeposit(new DepositRecord(
                        e.depositId(), e.to(), e.amount(), e.l1Block(), e.l1BlockHash(), new DepositState.Seen()));
                continue;
            }
            DepositRecord rec = existing.get();
            if (rec.l1BlockHash().equals(e.l1BlockHash())) {
                continue; // same block, nothing to do
            }
            switch (rec.state()) {
                case DepositState.Seen s -> store.saveDeposit(rec.reobserved(e.l1Block(), e.l1BlockHash(), e.to(), e.amount()));
                case DepositState.Confirmed c -> {
                    LOG.warn("[deposit-relayer] deposit #{} moved from block {} to {} before relaying", rec.id(), rec.l1Block(), e.l1Block());
                    store.saveDeposit(rec.transitionTo(new DepositState.Dropped())
                            .reobserved(e.l1Block(), e.l1BlockHash(), e.to(), e.amount()));
                }
                case DepositState.Dropped d -> store.saveDeposit(rec.reobserved(e.l1Block(), e.l1BlockHash(), e.to(), e.amount()));
                case DepositState.Relaying r -> LOG.warn(
                        "[deposit-relayer] deposit #{} changed block while a relay was in flight (needs operator attention)", rec.id());
                case DepositState.Done d -> reorgedAfterMint(rec);
            }
        }
        // Active deposits whose block hash is no longer canonical and which did not reappear: reorged away.
        for (DepositRecord rec : store.activeDeposits()) {
            boolean unsent = rec.state() instanceof DepositState.Seen || rec.state() instanceof DepositState.Confirmed;
            if (unsent && !seenNow.contains(rec.id()) && rec.l1Block() >= from && !isCanonical(rec)) {
                LOG.warn("[deposit-relayer] deposit #{} was in L1 block {} which is no longer canonical: dropped", rec.id(), rec.l1Block());
                store.saveDeposit(rec.transitionTo(new DepositState.Dropped()));
            }
        }
        store.setCursor(CURSOR, head);
    }

    private void advance(DepositRecord rec, long head) {
        switch (rec.state()) {
            case DepositState.Seen s -> {
                if (head - rec.l1Block() >= config.confirmations()) {
                    if (isCanonical(rec)) {
                        store.saveDeposit(rec.transitionTo(new DepositState.Confirmed()));
                        store.deposit(rec.id()).ifPresent(r -> relay(r));
                    } else {
                        store.saveDeposit(rec.transitionTo(new DepositState.Dropped()));
                    }
                }
            }
            case DepositState.Confirmed c -> relay(rec);
            case DepositState.Relaying r -> relay(rec); // resume after a crash: check L2 first, then maybe resend
            case DepositState.Done d -> { }
            case DepositState.Dropped d -> { }
        }
    }

    private void relay(DepositRecord rec) {
        // 1. Already minted on L2 (a previous attempt succeeded, or somebody else relayed it)?
        if (l2.isDepositProcessed(rec.id())) {
            LOG.info("[deposit-relayer] deposit #{} already processed on L2", rec.id());
            store.saveDeposit(rec.transitionTo(new DepositState.Done("already-processed")));
            return;
        }
        // 2. Canonical re-check right before sending.
        if (!isCanonical(rec)) {
            if (rec.state() instanceof DepositState.Confirmed) {
                LOG.warn("[deposit-relayer] deposit #{} block {} is not canonical any more, NOT relaying", rec.id(), rec.l1Block());
                store.saveDeposit(rec.transitionTo(new DepositState.Dropped()));
            }
            return;
        }
        // 3. Persist the intent, send, persist the result.
        DepositRecord relaying = rec.state() instanceof DepositState.Relaying ? rec : rec.transitionTo(new DepositState.Relaying());
        store.saveDeposit(relaying);
        try {
            TxResult tx = l2Writer.finalizeDeposit(rec.id(), rec.to(), rec.amount());
            if (tx.success()) {
                LOG.info("[deposit-relayer] minted deposit #{} on L2 in tx {}", rec.id(), tx.txHash());
                store.saveDeposit(relaying.transitionTo(new DepositState.Done(tx.txHash())));
            } else {
                LOG.warn("[deposit-relayer] L2 tx for deposit #{} failed", rec.id());
                store.saveDeposit(relaying.transitionTo(new DepositState.Confirmed()));
            }
        } catch (GatewayException e) {
            LOG.warn("[deposit-relayer] could not relay deposit #{}: {}", rec.id(), e.getMessage());
            store.saveDeposit(relaying.transitionTo(new DepositState.Confirmed()));
        }
    }

    /** The deposit was minted on L2 and now appears in another L1 block: the mint may be unbacked (see THREAT-MODEL). */
    private void reorgedAfterMint(DepositRecord rec) {
        LOG.warn("[deposit-relayer] deposit #{} was already minted but now appears in another L1 block: L2 may be unbacked", rec.id());
        metrics.counter("bridge_deposit_reorged_after_mint_total", "Deposits minted on L2 that later appeared in a different L1 block (unbacked mint suspected).").inc();
    }

    private boolean isCanonical(DepositRecord rec) {
        Optional<Bytes32> current = l1.canonicalHash(rec.l1Block());
        return current.isPresent() && current.get().equals(rec.l1BlockHash());
    }
}
