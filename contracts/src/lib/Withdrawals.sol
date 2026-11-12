// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

/// @notice A withdrawal from L2 to L1. `id` is the leaf index in the L2 withdrawal tree.
struct Withdrawal {
    uint256 id;
    address sender;
    address target;
    uint256 amount;
}

/// @title WithdrawalHash
/// @notice Leaf hashing for withdrawals.
/// @dev The leaf preimage is 128 bytes (four 32-byte words). Inner tree nodes hash exactly 64 bytes
///      (two `bytes32`). Different preimage lengths mean a leaf can never be passed off as an inner
///      node (and vice versa): that is the domain separation (see docs/SPEC.md).
library WithdrawalHash {
    function leaf(Withdrawal memory w) internal pure returns (bytes32) {
        return keccak256(abi.encode(w.id, w.sender, w.target, w.amount));
    }

    function leaf(uint256 id, address sender, address target, uint256 amount) internal pure returns (bytes32) {
        return keccak256(abi.encode(id, sender, target, amount));
    }
}
