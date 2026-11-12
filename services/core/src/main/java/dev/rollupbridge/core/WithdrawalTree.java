package dev.rollupbridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Java twin of the Solidity {@code IncrementalMerkleTree}: depth 20, commutative keccak pair hash, zero
 * subtree hashes for unused positions.
 *
 * <p>It deliberately contains TWO independent root algorithms: {@link #root()} replays the contract's
 * frontier algorithm (what L2 actually stores), {@link #rootAt(int)} builds the tree level by level (what
 * a prover needs to produce sibling lists). Tests assert they always agree, and both are checked against the
 * vectors produced by Solidity.
 */
public final class WithdrawalTree {
    public static final int DEPTH = 20;
    /** Like the deposit contract, the very last slot is never used. */
    public static final int MAX_LEAVES = (1 << DEPTH) - 1;

    private static final Bytes32[] ZEROS = new Bytes32[DEPTH + 1];

    static {
        ZEROS[0] = Bytes32.ZERO;
        for (int i = 0; i < DEPTH; i++) {
            ZEROS[i + 1] = hashPair(ZEROS[i], ZEROS[i]);
        }
    }

    private final List<Bytes32> leaves = new ArrayList<>();
    private final Bytes32[] branch = new Bytes32[DEPTH];

    /** Hash of an empty subtree of height {@code h}, 0..20. */
    public static Bytes32 zero(int h) {
        return ZEROS[h];
    }

    /** Commutative pair hash: sorted children, 64-byte preimage. */
    public static Bytes32 hashPair(Bytes32 a, Bytes32 b) {
        return a.compareTo(b) < 0
                ? Bytes32.of(Keccak256.hash(a.raw(), b.raw()))
                : Bytes32.of(Keccak256.hash(b.raw(), a.raw()));
    }

    public int size() {
        return leaves.size();
    }

    public List<Bytes32> leaves() {
        return Collections.unmodifiableList(leaves);
    }

    /** Appends a leaf (frontier algorithm identical to the contract) and returns its index. */
    public int append(Bytes32 leaf) {
        if (leaves.size() >= MAX_LEAVES) {
            throw new IllegalStateException("tree full");
        }
        int index = leaves.size();
        leaves.add(leaf);
        int size = index + 1;
        Bytes32 node = leaf;
        for (int h = 0; h < DEPTH; h++) {
            if ((size & 1) == 1) {
                branch[h] = node;
                return index;
            }
            node = hashPair(branch[h], node);
            size >>= 1;
        }
        return index;
    }

    /** Root as the contract computes it from its frontier. */
    public Bytes32 root() {
        int size = leaves.size();
        Bytes32 node = Bytes32.ZERO;
        Bytes32 zero = Bytes32.ZERO;
        for (int h = 0; h < DEPTH; h++) {
            node = (size & 1) == 1 ? hashPair(branch[h], node) : hashPair(node, zero);
            zero = hashPair(zero, zero);
            size >>= 1;
        }
        return node;
    }

    /** Root of the tree made of only the first {@code size} leaves (level-by-level construction). */
    public Bytes32 rootAt(int size) {
        return rootOf(leaves.subList(0, size));
    }

    /** Sibling list (length {@value #DEPTH}) proving leaf {@code index} in the tree of the first {@code size} leaves. */
    public List<Bytes32> proof(int index, int size) {
        if (index < 0 || index >= size || size > leaves.size()) {
            throw new IllegalArgumentException("index " + index + " not in the first " + size + " leaves");
        }
        List<Bytes32> level = new ArrayList<>(leaves.subList(0, size));
        List<Bytes32> proof = new ArrayList<>(DEPTH);
        int idx = index;
        for (int h = 0; h < DEPTH; h++) {
            int sibling = idx ^ 1;
            proof.add(sibling < level.size() ? level.get(sibling) : ZEROS[h]);
            level = nextLevel(level, h);
            idx >>= 1;
        }
        return proof;
    }

    /** Root of an arbitrary list of leaves, independent of any instance. */
    public static Bytes32 rootOf(List<Bytes32> leaves) {
        if (leaves.isEmpty()) {
            return ZEROS[DEPTH];
        }
        List<Bytes32> level = leaves;
        for (int h = 0; h < DEPTH; h++) {
            level = nextLevel(level, h);
        }
        return level.get(0);
    }

    /** Same check as OpenZeppelin {@code MerkleProof.verify}: fold the siblings with the commutative hash. */
    public static boolean verify(List<Bytes32> proof, Bytes32 root, Bytes32 leaf) {
        Bytes32 node = leaf;
        for (Bytes32 sibling : proof) {
            node = hashPair(node, sibling);
        }
        return node.equals(root);
    }

    private static List<Bytes32> nextLevel(List<Bytes32> level, int height) {
        List<Bytes32> next = new ArrayList<>((level.size() + 1) / 2);
        for (int i = 0; i < level.size(); i += 2) {
            Bytes32 right = i + 1 < level.size() ? level.get(i + 1) : ZEROS[height];
            next.add(hashPair(level.get(i), right));
        }
        return next;
    }
}
