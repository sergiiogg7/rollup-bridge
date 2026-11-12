// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

/// @notice Independent, memory-only reference implementation of the withdrawal tree (level by level, zero
///         padded to depth 20, commutative pair hash). Deliberately shares NO code with
///         `IncrementalMerkleTree`, so agreement between the two is evidence, not tautology.
library TreeBuilder {
    uint256 internal constant DEPTH = 20;

    function pair(bytes32 a, bytes32 b) internal pure returns (bytes32) {
        return a < b ? keccak256(abi.encodePacked(a, b)) : keccak256(abi.encodePacked(b, a));
    }

    function zero(uint256 h) internal pure returns (bytes32 z) {
        for (uint256 i = 0; i < h; ++i) {
            z = pair(z, z);
        }
    }

    function root(bytes32[] memory leaves) internal pure returns (bytes32) {
        if (leaves.length == 0) return zero(DEPTH);
        bytes32[] memory level = leaves;
        bytes32 z;
        for (uint256 h = 0; h < DEPTH; ++h) {
            uint256 n = (level.length + 1) / 2;
            bytes32[] memory next = new bytes32[](n);
            for (uint256 i = 0; i < n; ++i) {
                bytes32 left = level[2 * i];
                bytes32 right = 2 * i + 1 < level.length ? level[2 * i + 1] : z;
                next[i] = pair(left, right);
            }
            level = next;
            z = pair(z, z);
        }
        return level[0];
    }

    /// @dev Sibling list for leaf `index` of the tree made of `leaves` (length DEPTH).
    function proof(bytes32[] memory leaves, uint256 index) internal pure returns (bytes32[] memory p) {
        require(index < leaves.length, "index out of range");
        p = new bytes32[](DEPTH);
        bytes32[] memory level = leaves;
        bytes32 z;
        uint256 idx = index;
        for (uint256 h = 0; h < DEPTH; ++h) {
            uint256 sib = idx ^ 1;
            p[h] = sib < level.length ? level[sib] : z;
            uint256 n = (level.length + 1) / 2;
            bytes32[] memory next = new bytes32[](n);
            for (uint256 i = 0; i < n; ++i) {
                next[i] = pair(level[2 * i], 2 * i + 1 < level.length ? level[2 * i + 1] : z);
            }
            level = next;
            z = pair(z, z);
            idx >>= 1;
        }
    }

    /// @dev Prefix `leaves[0:count]`.
    function head(bytes32[] memory leaves, uint256 count) internal pure returns (bytes32[] memory out) {
        out = new bytes32[](count);
        for (uint256 i = 0; i < count; ++i) {
            out[i] = leaves[i];
        }
    }
}
