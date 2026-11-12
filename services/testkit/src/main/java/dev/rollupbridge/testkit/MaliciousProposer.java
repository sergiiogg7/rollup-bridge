package dev.rollupbridge.testkit;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Keccak256;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import java.math.BigInteger;
import java.util.List;

/**
 * A proposer that lies. Lives in the testkit only: it exists to prove that the challenger catches each kind of
 * lie. It uses the same {@link L1Writer} as the honest proposer, i.e. it holds the proposer role key.
 */
public final class MaliciousProposer {

    /** The lie to tell. */
    public enum Mode {
        /** Commit to a tree that contains a withdrawal L2 never emitted (steal funds). */
        FAKE_LEAF,
        /** Real root, wrong withdrawal count. */
        WRONG_COUNT,
        /** Real root and count, but a block hash from nowhere. */
        WRONG_BLOCK_HASH,
        /** A root that has nothing to do with this L2 (e.g. computed on another chain). */
        FOREIGN_ROOT
    }

    /** What was posted: the transaction and, for FAKE_LEAF, the fabricated withdrawal. */
    public record Posted(TxResult tx, Withdrawal fakeWithdrawal, List<Bytes32> fakeProof) {}

    private final L2Reader l2;
    private final L1Writer l1Writer;

    public MaliciousProposer(L2Reader l2, L1Writer l1Writer) {
        this.l2 = l2;
        this.l1Writer = l1Writer;
    }

    public Posted propose(Mode mode, String attacker, BigInteger stolenAmount) {
        long head = l2.headNumber();
        WithdrawalTree tree = new WithdrawalTree();
        for (WithdrawalEvent e : l2.withdrawalEvents(0, head)) {
            tree.append(e.withdrawal().leaf());
        }
        Bytes32 realHash = l2.blockHash(head).orElseThrow();
        long count = tree.size();
        return switch (mode) {
            case FAKE_LEAF -> {
                Withdrawal fake = new Withdrawal(count, attacker, attacker, stolenAmount);
                tree.append(fake.leaf());
                TxResult tx = l1Writer.proposeOutput(tree.root(), realHash, head, count + 1);
                yield new Posted(tx, fake, tree.proof((int) count, (int) count + 1));
            }
            case WRONG_COUNT -> new Posted(l1Writer.proposeOutput(tree.root(), realHash, head, count + 1), null, null);
            case WRONG_BLOCK_HASH ->
                new Posted(l1Writer.proposeOutput(tree.root(), Bytes32.of(Keccak256.hash("not a block")), head, count), null, null);
            case FOREIGN_ROOT ->
                new Posted(l1Writer.proposeOutput(Bytes32.of(Keccak256.hash("another chain")), realHash, head, count), null, null);
        };
    }
}
