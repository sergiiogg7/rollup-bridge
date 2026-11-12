package dev.rollupbridge.services;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L2Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Independent check of a posted output against L2.
 *
 * <p>It never trusts the proposer and never trusts the event fields {@code leaf} / {@code newRoot}: it takes the
 * raw withdrawal fields from the L2 logs up to the output block, recomputes every leaf, rebuilds the tree and
 * compares root, count and block hash with what was posted on L1. Used by the challenger (to decide whether to
 * challenge) and by the guardian (to decide a challenge). The two should be wired to DIFFERENT L2 RPC endpoints
 * in a real deployment.
 */
public final class OutputVerifier {

    /** Outcome of a check. */
    public sealed interface Verdict {
        record Valid() implements Verdict {}

        record Invalid(List<String> reasons) implements Verdict {}
    }

    private final L2Reader l2;

    public OutputVerifier(L2Reader l2) {
        this.l2 = l2;
    }

    public Verdict verify(OutputRecord output) {
        List<String> reasons = new ArrayList<>();
        long block = output.l2BlockNumber();

        Optional<Bytes32> realHash = l2.blockHash(block);
        if (realHash.isEmpty()) {
            reasons.add("L2 block " + block + " does not exist");
        } else if (!realHash.get().equals(output.l2BlockHash())) {
            reasons.add("block hash mismatch at L2 block " + block + ": posted " + output.l2BlockHash() + ", real " + realHash.get());
        }

        WithdrawalTree tree = new WithdrawalTree();
        if (realHash.isPresent() && block > l2.headNumber()) {
            // the block exists but this node's head says otherwise: a lagging or inconsistent answer, not a lie
            throw new GatewayException("L2 node is inconsistent about its head (block " + block + " exists but is above the head): inconclusive");
        }
        if (block <= l2.headNumber()) {
            // A flaky node may repeat or reorder logs: that is noise, not a lie. Dedupe by id and sort; a GAP means the
            // node withheld a log, so the check is inconclusive (retry on the next tick) instead of a false accusation.
            Map<Long, WithdrawalEvent> byId = new TreeMap<>();
            for (WithdrawalEvent e : l2.withdrawalEvents(0, block)) {
                WithdrawalEvent previous = byId.putIfAbsent(e.withdrawal().id(), e);
                if (previous != null && !previous.withdrawal().leaf().equals(e.withdrawal().leaf())) {
                    throw new GatewayException("L2 node returned two different withdrawals with id " + e.withdrawal().id());
                }
            }
            for (WithdrawalEvent e : byId.values()) {
                if (e.withdrawal().id() != tree.size()) {
                    throw new GatewayException("L2 node returned incomplete withdrawal logs (missing id " + tree.size() + "): inconclusive");
                }
                tree.append(e.withdrawal().leaf());
            }
        }
        if (tree.size() != output.withdrawalCount()) {
            reasons.add("withdrawal count mismatch: posted " + output.withdrawalCount() + ", real " + tree.size());
        }
        if (!tree.root().equals(output.withdrawalRoot())) {
            reasons.add("root mismatch: posted " + output.withdrawalRoot() + ", real " + tree.root());
        }
        return reasons.isEmpty() ? new Verdict.Valid() : new Verdict.Invalid(reasons);
    }
}
