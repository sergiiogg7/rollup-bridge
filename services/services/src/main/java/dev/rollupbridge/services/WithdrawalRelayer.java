package dev.rollupbridge.services;

import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.Keccak256;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.model.ProofInfo;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.state.WithdrawalRecord;
import dev.rollupbridge.core.state.WithdrawalState;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries each withdrawal from L2 to a payout on L1: ingest the L2 event, wait for a live output that covers it
 * and matches the relayer own copy of the tree, prove, wait for the window, finalize. If the output it relied
 * on is deleted it goes back and proves against the next valid one.
 *
 * <p>Idempotent and crash-safe because every step first <i>reconciles with the chain</i>: if the contract
 * already knows the proof (or the finalization), the relayer records that instead of sending again.
 */
public final class WithdrawalRelayer implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(WithdrawalRelayer.class);
    /** Name of the persisted L2 scan cursor (also read by the metrics collector to compute the lag). */
    public static final String CURSOR = "withdrawal.l2ScannedUpTo";
    private static final String FINALIZED_TOPIC =
            Hex.encode(Keccak256.hash("WithdrawalFinalized(bytes32,address,uint256,bool)"));

    private final L1Reader l1;
    private final L1Writer l1Writer;
    private final L2Reader l2;
    private final BridgeStore store;

    public WithdrawalRelayer(L1Reader l1, L1Writer l1Writer, L2Reader l2, BridgeStore store) {
        this.l1 = l1;
        this.l1Writer = l1Writer;
        this.l2 = l2;
        this.store = store;
    }

    @Override
    public String name() {
        return "withdrawal-relayer";
    }

    @Override
    public void tick() {
        ingest();
        WithdrawalTree tree = ownTree();
        recheckRecentlyFinalized();
        for (WithdrawalRecord rec : store.activeWithdrawals()) {
            try {
                advance(rec, tree);
            } catch (GatewayException e) {
                LOG.warn("[withdrawal-relayer] withdrawal #{} step failed, will retry: {}", rec.withdrawal().id(), e.getMessage());
            }
        }
    }

    /** How many of the newest finalized withdrawals are re-checked against L1 each tick (reorgs only reach the tip). */
    private static final int RECHECK_FINALIZED = 32;

    /**
     * A {@code finalizeWithdrawal} transaction that was mined can still be removed by an L1 reorg. The store says
     * Finalized but the chain says otherwise: start over (the proof may be gone as well, which {@code prove} handles).
     */
    private void recheckRecentlyFinalized() {
        List<WithdrawalRecord> all = store.allWithdrawals();
        int checked = 0;
        for (int i = all.size() - 1; i >= 0 && checked < RECHECK_FINALIZED; i--) {
            WithdrawalRecord rec = all.get(i);
            if (!(rec.state() instanceof WithdrawalState.Finalized)) {
                continue;
            }
            checked++;
            if (!l1.isFinalized(rec.withdrawal().leaf())) {
                LOG.warn("[withdrawal-relayer] withdrawal #{} was finalized but no longer is on L1 (reorg): starting over", rec.withdrawal().id());
                store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Invalidated(-1)));
            }
        }
    }

    /** Copies new {@code WithdrawalInitiated} events into the store. */
    private void ingest() {
        long head = l2.headNumber();
        long from = store.cursor(CURSOR).orElse(-1) + 1;
        for (WithdrawalEvent e : l2.withdrawalEvents(from, head)) {
            if (store.withdrawal(e.withdrawal().id()).isEmpty()) {
                LOG.info("[withdrawal-relayer] new withdrawal #{}: {} wei to {}", e.withdrawal().id(), e.withdrawal().amount(), e.withdrawal().target());
                store.saveWithdrawal(new WithdrawalRecord(e.withdrawal(), e.l2Block(), new WithdrawalState.Initiated()));
            }
        }
        store.setCursor(CURSOR, head);
    }

    /** The relayer own tree from every withdrawal it knows (leaves recomputed, never taken from events). */
    private WithdrawalTree ownTree() {
        WithdrawalTree tree = new WithdrawalTree();
        for (WithdrawalRecord r : store.allWithdrawals()) {
            if (r.withdrawal().id() != tree.size()) {
                break; // a gap: stop, later ticks will fill it
            }
            tree.append(r.withdrawal().leaf());
        }
        return tree;
    }

    private void advance(WithdrawalRecord rec, WithdrawalTree tree) {
        Withdrawal w = rec.withdrawal();
        Bytes32 leaf = w.leaf();

        // Reconcile with the chain first.
        if (l1.isFinalized(leaf)) {
            LOG.info("[withdrawal-relayer] withdrawal #{} is already finalized on L1", w.id());
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Finalized("observed-on-chain", true)));
            return;
        }
        switch (rec.state()) {
            case WithdrawalState.Initiated i -> include(rec, tree);
            case WithdrawalState.Invalidated i -> include(rec, tree);
            case WithdrawalState.Included inc -> prove(rec, inc.outputIndex(), tree);
            case WithdrawalState.Proven p -> finalizeIfReady(rec, p.outputIndex());
            case WithdrawalState.Finalized f -> { }
        }
    }

    /** Finds the oldest live output that covers the withdrawal and matches our own tree. */
    private void include(WithdrawalRecord rec, WithdrawalTree tree) {
        Withdrawal w = rec.withdrawal();
        long n = l1.outputCount();
        for (long i = 0; i < n; i++) {
            OutputRecord o = l1.output(i);
            if (o.status() != OutputStatus.PROPOSED || w.id() >= o.withdrawalCount()) {
                continue;
            }
            if (tree.size() < o.withdrawalCount()) {
                continue; // we have not seen all of its withdrawals yet
            }
            if (!tree.rootAt((int) o.withdrawalCount()).equals(o.withdrawalRoot())) {
                LOG.warn("[withdrawal-relayer] output #{} root does not match our tree: not using it (challenger's job)", i);
                continue;
            }
            LOG.info("[withdrawal-relayer] withdrawal #{} is covered by output #{}", w.id(), i);
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Included(i)));
            prove(store.withdrawal(w.id()).orElseThrow(), i, tree);
            return;
        }
    }

    private void prove(WithdrawalRecord rec, long outputIndex, WithdrawalTree tree) {
        Withdrawal w = rec.withdrawal();
        OutputRecord o = load(outputIndex);
        if (o == null || o.status() == OutputStatus.DELETED) {
            LOG.warn("[withdrawal-relayer] output #{} was deleted; withdrawal #{} must be proven again", outputIndex, w.id());
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Invalidated(outputIndex)));
            return;
        }
        if (o.status() == OutputStatus.CHALLENGED) {
            return; // wait for the verdict
        }
        // Did a previous run already prove it (crash between the L1 transaction and our store write)?
        ProofInfo onChain = l1.proof(w.leaf());
        if (onChain.exists() && l1.output(onChain.outputIndex()).status() != OutputStatus.DELETED) {
            LOG.info("[withdrawal-relayer] withdrawal #{} already proven on L1 against output #{}", w.id(), onChain.outputIndex());
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Proven(onChain.outputIndex(), "observed-on-chain")));
            return;
        }
        if (l1.isPaused()) {
            return;
        }
        List<Bytes32> proof = tree.proof((int) w.id(), (int) o.withdrawalCount());
        TxResult tx = l1Writer.proveWithdrawal(w, outputIndex, proof);
        if (tx.success()) {
            LOG.info("[withdrawal-relayer] proved withdrawal #{} against output #{} in tx {}", w.id(), outputIndex, tx.txHash());
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Proven(outputIndex, tx.txHash())));
        }
    }

    private void finalizeIfReady(WithdrawalRecord rec, long outputIndex) {
        Withdrawal w = rec.withdrawal();
        // The contract finalizes against ITS proof, not against what we remember: after an L1 reorg the proof can be
        // gone (the prove transaction was removed) or point at another output.
        ProofInfo onChain = l1.proof(w.leaf());
        if (!onChain.exists()) {
            LOG.warn("[withdrawal-relayer] the proof of withdrawal #{} is no longer on L1 (reorg): proving again", w.id());
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Invalidated(outputIndex)));
            return;
        }
        outputIndex = onChain.outputIndex();
        OutputRecord o = load(outputIndex);
        if (o == null || o.status() == OutputStatus.DELETED) {
            LOG.warn("[withdrawal-relayer] output #{} was deleted after withdrawal #{} was proven: back to Invalidated", outputIndex, w.id());
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Invalidated(outputIndex)));
            return;
        }
        if (l1.isPaused() || !l1.isFinalizable(outputIndex)) {
            return; // window still open, challenged, or bridge paused
        }
        TxResult tx = l1Writer.finalizeWithdrawal(w);
        if (tx.success()) {
            boolean paid = paidFlag(tx);
            LOG.info("[withdrawal-relayer] finalized withdrawal #{} in tx {} ({})", w.id(), tx.txHash(), paid ? "paid" : "credited to claimable");
            store.saveWithdrawal(rec.transitionTo(new WithdrawalState.Finalized(tx.txHash(), paid)));
        }
    }

    private static boolean paidFlag(TxResult tx) {
        for (TxResult.LogEntry log : tx.logs()) {
            if (!log.topics().isEmpty() && log.topics().get(0).equalsIgnoreCase(FINALIZED_TOPIC)) {
                return Abi.Words.of(log.data()).bool(1);
            }
        }
        return true;
    }

    /** The output, or null if it no longer exists (an L1 reorg can remove a {@code proposeOutput} transaction). */
    private OutputRecord load(long index) {
        return index < l1.outputCount() ? l1.output(index) : null;
    }
}
