// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

/// @title IncrementalMerkleTree
/// @notice Append-only Merkle tree of fixed depth 20 (2**20 - 1 usable leaves). Same algorithm as the
///         Ethereum deposit contract: only the left "frontier" (`branch`) is stored, so an insert costs
///         O(depth) and the root is recomputed from the frontier and the zero-subtree hashes.
/// @dev Pair hashing is COMMUTATIVE (the two children are sorted before hashing), exactly like
///      OpenZeppelin's `MerkleProof`. That makes proofs plain sibling lists with no left/right flags and
///      lets `MerkleProof.verify` check them. Unused positions hold the zero subtree hashes:
///      `zeros[0] = 0`, `zeros[i + 1] = hashPair(zeros[i], zeros[i])`.
///      Analogy for a Java developer: the tree is a rolling checksum that supports "prove this record
///      is part of the checksummed set" without sending the whole set.
library IncrementalMerkleTree {
    uint256 internal constant DEPTH = 20;
    /// @dev One less than 2**DEPTH, like the deposit contract: a completely full tree would need one
    ///      more frontier slot, so the last leaf is deliberately never accepted.
    uint256 internal constant MAX_LEAVES = 2 ** DEPTH - 1;

    /// @dev `branch[h]` is the pending left node at height `h` (meaningful where bit `h` of `count` is 1).
    struct Tree {
        uint256 count;
        bytes32[DEPTH] branch;
    }

    error TreeFull();

    /// @notice Appends `leaf` and returns its index.
    function insert(Tree storage tree, bytes32 leaf) internal returns (uint256 index) {
        uint256 size = tree.count;
        if (size >= MAX_LEAVES) revert TreeFull();
        index = size;
        size += 1;
        tree.count = size;

        bytes32 node = leaf;
        for (uint256 h = 0; h < DEPTH; ++h) {
            if (size & 1 == 1) {
                tree.branch[h] = node;
                return index;
            }
            node = hashPair(tree.branch[h], node);
            size >>= 1;
        }
        // Falling out of the loop is not possible: size <= 2**DEPTH - 1 always has a set bit below DEPTH.
    }

    /// @notice Current root. For an empty tree this is `zeroHash(DEPTH)`.
    function root(Tree storage tree) internal view returns (bytes32 node) {
        uint256 size = tree.count;
        bytes32 zero; // zero-subtree hash of the current height
        for (uint256 h = 0; h < DEPTH; ++h) {
            if (size & 1 == 1) {
                node = hashPair(tree.branch[h], node);
            } else {
                node = hashPair(node, zero);
            }
            zero = hashPair(zero, zero);
            size >>= 1;
        }
    }

    /// @notice Commutative pair hash (sorted children), 64-byte preimage.
    function hashPair(bytes32 a, bytes32 b) internal pure returns (bytes32 h) {
        assembly ("memory-safe") {
            switch lt(a, b)
            case 1 {
                mstore(0x00, a)
                mstore(0x20, b)
            }
            default {
                mstore(0x00, b)
                mstore(0x20, a)
            }
            h := keccak256(0x00, 0x40)
        }
    }

    /// @notice Hash of an all-empty subtree of height `h` (`h` in 0..DEPTH).
    function zeroHash(uint256 h) internal pure returns (bytes32 z) {
        for (uint256 i = 0; i < h; ++i) {
            z = hashPair(z, z);
        }
    }
}
