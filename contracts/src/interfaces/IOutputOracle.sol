// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

/// @notice Lifecycle of a proposed output. `Challenged` outputs can neither finalize withdrawals nor be proven against.
enum OutputStatus {
    Proposed,
    Challenged,
    Deleted
}

/// @notice A claim about L2 posted on L1: "at L2 block `l2BlockNumber` (hash `l2BlockHash`) the withdrawal
///         tree had `withdrawalCount` leaves and root `withdrawalRoot`".
struct Output {
    bytes32 withdrawalRoot;
    bytes32 l2BlockHash;
    uint64 l2BlockNumber;
    uint64 withdrawalCount;
    uint64 proposedAt;
    OutputStatus status;
    address proposer;
    address challenger;
    uint64 challengedAt;
    bool bondSettled;
}

interface IOutputOracle {
    function guardian() external view returns (address);
    function outputCount() external view returns (uint256);
    function getOutput(uint256 index) external view returns (Output memory);
    function isFinalizable(uint256 index) external view returns (bool);
}
