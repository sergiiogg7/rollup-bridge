// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable, Ownable2Step} from "@openzeppelin/contracts/access/Ownable2Step.sol";
import {BridgedETH} from "./BridgedETH.sol";
import {IncrementalMerkleTree} from "../lib/IncrementalMerkleTree.sol";
import {WithdrawalHash} from "../lib/Withdrawals.sol";

/// @title L2Bridge
/// @notice L2 side of the bridge. Deposits relayed from L1 mint `BridgedETH`; withdrawals burn it and
///         append a leaf to an incremental Merkle tree whose root the proposer later posts to L1.
/// @dev Trust: the `relayer` is trusted to mirror L1 deposits faithfully (a bad relayer can mint
///      unbacked tokens on L2; the L1 side then cannot pay them out, see THREAT-MODEL.md).
contract L2Bridge is Ownable2Step {
    using IncrementalMerkleTree for IncrementalMerkleTree.Tree;

    BridgedETH public immutable TOKEN;

    /// @notice Account allowed to call `finalizeDeposit` (the deposit relayer service).
    address public relayer;

    /// @notice Replay guard: an L1 deposit id can be minted at most once.
    mapping(uint256 depositId => bool) public processedDeposits;

    IncrementalMerkleTree.Tree private _tree;

    event DepositFinalized(uint256 indexed depositId, address indexed to, uint256 amount);
    event WithdrawalInitiated(
        uint256 indexed id,
        address indexed sender,
        address indexed target,
        uint256 amount,
        bytes32 leaf,
        bytes32 newRoot
    );
    event RelayerUpdated(address indexed relayer);

    error NotRelayer();
    error DepositAlreadyProcessed(uint256 depositId);
    error ZeroAddress();
    error ZeroAmount();

    modifier onlyRelayer() {
        if (msg.sender != relayer) revert NotRelayer();
        _;
    }

    constructor(address owner_, address relayer_) Ownable(owner_) {
        if (relayer_ == address(0)) revert ZeroAddress();
        relayer = relayer_;
        TOKEN = new BridgedETH(address(this));
        emit RelayerUpdated(relayer_);
    }

    function setRelayer(address relayer_) external onlyOwner {
        if (relayer_ == address(0)) revert ZeroAddress();
        relayer = relayer_;
        emit RelayerUpdated(relayer_);
    }

    // Slither "reentrancy-events": the events after TOKEN.mint/burn are emitted after calls to the bridge's
    // own BridgedETH (a trusted contract deployed by this one, no hooks, no callbacks). Emitting after the
    // state change keeps the event faithful to what actually happened.
    // slither-disable-start reentrancy-events
    /// @notice Mints the deposit made on L1. Only the relayer; each `depositId` works once.
    function finalizeDeposit(uint256 depositId, address to, uint256 amount) external onlyRelayer {
        if (processedDeposits[depositId]) revert DepositAlreadyProcessed(depositId);
        processedDeposits[depositId] = true;
        TOKEN.mint(to, amount);
        emit DepositFinalized(depositId, to, amount);
    }

    /// @notice Burns `amount` of the caller's `BridgedETH` and records a withdrawal to `target` on L1.
    function initiateWithdrawal(address target, uint256 amount) external returns (uint256 id) {
        if (target == address(0)) revert ZeroAddress();
        if (amount == 0) revert ZeroAmount();
        TOKEN.burn(msg.sender, amount);
        bytes32 leaf = WithdrawalHash.leaf(_tree.count, msg.sender, target, amount);
        id = _tree.insert(leaf);
        emit WithdrawalInitiated(id, msg.sender, target, amount, leaf, _tree.root());
    }

    // slither-disable-end reentrancy-events

    /// @notice Root of the withdrawal tree (what the proposer commits to on L1).
    function withdrawalRoot() external view returns (bytes32) {
        return _tree.root();
    }

    /// @notice Number of withdrawals so far (the next withdrawal id).
    function withdrawalCount() external view returns (uint256) {
        return _tree.count;
    }
}
