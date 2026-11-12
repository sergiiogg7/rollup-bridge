// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable, Ownable2Step} from "@openzeppelin/contracts/access/Ownable2Step.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";
import {IOutputOracle, Output, OutputStatus} from "../interfaces/IOutputOracle.sol";
import {IncrementalMerkleTree} from "../lib/IncrementalMerkleTree.sol";
import {Withdrawal, WithdrawalHash} from "../lib/Withdrawals.sol";

/// @title L1Bridge
/// @notice L1 side of the bridge. Locks ETH on deposit; pays withdrawals that were (1) proven against a
///         posted output root and (2) survived the challenge window.
/// @dev Withdrawal flow, in Java terms: `proveWithdrawal` is "register a claim with evidence",
///      `finalizeWithdrawal` is "execute it once the dispute period is over". Payout order is
///      checks-effects-interactions plus a reentrancy guard; a target that rejects ETH is credited to a
///      pull-payment balance so one hostile recipient can never block the queue.
contract L1Bridge is Ownable2Step, ReentrancyGuard {
    /// @notice What the bridge remembers about a proven withdrawal.
    struct Proof {
        uint64 outputIndex;
        uint64 provenAt;
        bool exists;
    }

    IOutputOracle public immutable ORACLE;

    /// @notice Largest single deposit (wei). Early-stage safety limit.
    uint256 public maxDepositAmount;
    /// @notice Largest amount of ETH the bridge may hold on behalf of L2 holders (wei).
    uint256 public maxTotalLocked;

    uint256 public depositCount;
    /// @notice Deposited minus finalized withdrawals: what L2 holders can still claim. Never negative.
    uint256 public totalLocked;
    /// @notice ETH credited to targets whose transfer failed and not yet claimed.
    uint256 public totalClaimable;
    bool public paused;

    mapping(bytes32 leaf => Proof) public proofs;
    mapping(bytes32 leaf => bool) public finalized;
    mapping(address account => uint256) public claimable;

    event DepositInitiated(uint256 indexed depositId, address indexed from, address indexed to, uint256 amount);
    event WithdrawalProven(bytes32 indexed leaf, uint256 indexed outputIndex, uint256 id);
    event WithdrawalFinalized(bytes32 indexed leaf, address indexed target, uint256 amount, bool paid);
    event Claimed(address indexed account, uint256 amount);
    event PausedSet(bool paused);
    event LimitsUpdated(uint256 maxDepositAmount, uint256 maxTotalLocked);

    error NotGuardian();
    error IsPaused();
    error ZeroAddress();
    error ZeroAmount();
    error DepositTooLarge(uint256 amount, uint256 max);
    error TotalLockedExceeded();
    error BadProofLength();
    error OutputNotProvable(OutputStatus status);
    error IdOutOfRange(uint256 id, uint64 count);
    error InvalidProof();
    error AlreadyProven();
    error AlreadyFinalized();
    error NotProven();
    error OutputNotFinalizable();
    error InsufficientLocked();
    error NothingToClaim();
    error TransferFailed();

    modifier whenNotPaused() {
        if (paused) revert IsPaused();
        _;
    }

    constructor(address owner_, IOutputOracle oracle, uint256 maxDepositAmount_, uint256 maxTotalLocked_)
        Ownable(owner_)
    {
        if (address(oracle) == address(0)) revert ZeroAddress();
        ORACLE = oracle;
        maxDepositAmount = maxDepositAmount_;
        maxTotalLocked = maxTotalLocked_;
        emit LimitsUpdated(maxDepositAmount_, maxTotalLocked_);
    }

    // ---------------------------------------------------------------------------------------------
    // Administration
    // ---------------------------------------------------------------------------------------------

    function setLimits(uint256 maxDepositAmount_, uint256 maxTotalLocked_) external onlyOwner {
        maxDepositAmount = maxDepositAmount_;
        maxTotalLocked = maxTotalLocked_;
        emit LimitsUpdated(maxDepositAmount_, maxTotalLocked_);
    }

    /// @notice Stops deposits, proving and finalizing. Never blocks `claim`.
    function pause() external {
        if (msg.sender != ORACLE.guardian()) revert NotGuardian();
        paused = true;
        emit PausedSet(true);
    }

    function unpause() external {
        if (msg.sender != ORACLE.guardian()) revert NotGuardian();
        paused = false;
        emit PausedSet(false);
    }

    // ---------------------------------------------------------------------------------------------
    // Deposits
    // ---------------------------------------------------------------------------------------------

    /// @notice Locks `msg.value` and emits the event the deposit relayer mints against on L2.
    /// @param to Recipient on L2.
    function depositETH(address to) external payable whenNotPaused returns (uint256 depositId) {
        if (to == address(0)) revert ZeroAddress();
        if (msg.value == 0) revert ZeroAmount();
        if (msg.value > maxDepositAmount) revert DepositTooLarge(msg.value, maxDepositAmount);
        if (totalLocked + msg.value > maxTotalLocked) revert TotalLockedExceeded();
        totalLocked += msg.value;
        depositId = depositCount++;
        emit DepositInitiated(depositId, msg.sender, to, msg.value);
    }

    // ---------------------------------------------------------------------------------------------
    // Withdrawals
    // ---------------------------------------------------------------------------------------------

    // Slither "timestamp" is a false positive here: block.timestamp is only stored (provenAt), never used
    // to gate anything; the comparison it flags is on an enum status. The real window check lives in the oracle.
    // slither-disable-start timestamp
    /// @notice Registers a withdrawal as proven against output `outputIndex`. Re-proving is only possible when
    ///         the output used before was deleted.
    /// @param proof Sibling hashes from the leaf to the root (exactly `IncrementalMerkleTree.DEPTH` of them).
    function proveWithdrawal(Withdrawal calldata w, uint256 outputIndex, bytes32[] calldata proof)
        external
        whenNotPaused
    {
        if (proof.length != IncrementalMerkleTree.DEPTH) revert BadProofLength();
        Output memory o = ORACLE.getOutput(outputIndex);
        if (o.status != OutputStatus.Proposed) revert OutputNotProvable(o.status);
        if (w.id >= o.withdrawalCount) revert IdOutOfRange(w.id, o.withdrawalCount);

        bytes32 leaf = WithdrawalHash.leaf(w);
        if (finalized[leaf]) revert AlreadyFinalized();
        if (!MerkleProof.verifyCalldata(proof, o.withdrawalRoot, leaf)) revert InvalidProof();

        Proof memory previous = proofs[leaf];
        if (previous.exists && ORACLE.getOutput(previous.outputIndex).status != OutputStatus.Deleted) {
            revert AlreadyProven();
        }
        // forge-lint: disable-next-line(unsafe-typecast)
        proofs[leaf] = Proof({outputIndex: uint64(outputIndex), provenAt: uint64(block.timestamp), exists: true});
        emit WithdrawalProven(leaf, outputIndex, w.id);
    }

    // slither-disable-end timestamp

    /// @notice Pays a proven withdrawal once its output is finalizable. Callable by anyone (the relayer, or
    ///         the user themselves); the money always goes to `w.target`.
    function finalizeWithdrawal(Withdrawal calldata w) external whenNotPaused nonReentrant {
        bytes32 leaf = WithdrawalHash.leaf(w);
        if (finalized[leaf]) revert AlreadyFinalized();
        Proof memory p = proofs[leaf];
        if (!p.exists) revert NotProven();
        if (!ORACLE.isFinalizable(p.outputIndex)) revert OutputNotFinalizable();
        if (w.amount > totalLocked) revert InsufficientLocked();

        // Effects before interaction.
        finalized[leaf] = true;
        totalLocked -= w.amount;

        bool paid = _send(w.target, w.amount);
        if (!paid) {
            claimable[w.target] += w.amount;
            totalClaimable += w.amount;
        }
        emit WithdrawalFinalized(leaf, w.target, w.amount, paid);
    }

    /// @notice Withdraws what failed payouts credited to the caller. Works while paused.
    function claim() external nonReentrant {
        uint256 amount = claimable[msg.sender];
        if (amount == 0) revert NothingToClaim();
        claimable[msg.sender] = 0;
        totalClaimable -= amount;
        emit Claimed(msg.sender, amount);
        if (!_send(msg.sender, amount)) revert TransferFailed();
    }

    /// @dev Plain value call. Return data is deliberately not copied: a target returning a huge payload
    ///      must not be able to burn the relayer's gas ("return bomb").
    function _send(address to, uint256 amount) private returns (bool ok) {
        assembly ("memory-safe") {
            ok := call(gas(), to, amount, 0, 0, 0, 0)
        }
    }
}
