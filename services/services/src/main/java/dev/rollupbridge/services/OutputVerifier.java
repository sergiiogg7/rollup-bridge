package dev.rollupbridge.services;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.L2Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        boolean contiguous = true;
        if (block <= l2.headNumber()) {
            for (WithdrawalEvent e : l2.withdrawalEvents(0, block)) {
                if (e.withdrawal().id() != tree.size()) {
                    contiguous = false;
                    break;
                }
                tree.append(e.withdrawal().leaf());
            }
        }
        if (!contiguous) {
            reasons.add("L2 withdrawal ids are not contiguous (node returned inconsistent logs)");
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
