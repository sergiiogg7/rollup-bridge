// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable, Ownable2Step} from "@openzeppelin/contracts/access/Ownable2Step.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {IOutputOracle, Output, OutputStatus} from "../interfaces/IOutputOracle.sol";

/// @title OutputOracle
/// @notice Stores the L2 "outputs" (withdrawal-tree roots) that the L1 bridge accepts withdrawal proofs
///         against. Optimistic: an output is accepted unless somebody challenges it inside the
///         challenge window. Both sides post bonds. MVP trust assumption: a GUARDIAN resolves
///         challenges (a real system would verify a fault proof on-chain instead).
/// @dev Each output commits to the WHOLE withdrawal tree up to its block, so a bad output taints every
///      later one: deleting output `i` also deletes every output after it. While any challenge is open,
///      NO output is finalizable (see `isFinalizable`), so a bad later output can never slip through
///      while an earlier one is being judged.
///      Bonds are pull-payments (`credits` + `withdrawCredits`) so a hostile recipient cannot block
///      resolution. Analogy: this contract is the "ledger of claims" with an escrow and an arbiter.
contract OutputOracle is IOutputOracle, Ownable2Step, ReentrancyGuard {
    // Slither "timestamp" is silenced for the whole contract on purpose: the challenge and resolution
    // windows are defined in block.timestamp. A validator can skew it by seconds, which is irrelevant
    // for windows of hours or days (and the bridge treats them as lower bounds on age).
    // slither-disable-start timestamp
    /// @notice Seconds an output can be challenged, and the minimum age before it can back a payout.
    uint256 public immutable CHALLENGE_WINDOW;
    /// @notice Seconds the guardian has to resolve a challenge; after that anyone can expire it.
    uint256 public immutable RESOLUTION_PERIOD;
    uint256 public immutable PROPOSER_BOND;
    uint256 public immutable CHALLENGER_BOND;

    address public proposer;
    address public guardian;

    /// @notice Number of outputs currently in `Challenged` state. While > 0 nothing finalizes.
    uint256 public openChallenges;

    /// @notice Pull-payment balances (bonds returned or won).
    mapping(address account => uint256) public credits;

    Output[] private _outputs;

    event OutputProposed(
        uint256 indexed index,
        address indexed proposer,
        bytes32 withdrawalRoot,
        bytes32 l2BlockHash,
        uint64 l2BlockNumber,
        uint64 withdrawalCount
    );
    event OutputChallenged(uint256 indexed index, address indexed challenger);
    event ChallengeResolved(uint256 indexed index, bool proposerWasWrong);
    event ChallengeExpired(uint256 indexed index);
    event OutputDeleted(uint256 indexed index);
    event ProposerBondClaimed(uint256 indexed index, address indexed proposer);
    event CreditsWithdrawn(address indexed account, uint256 amount);
    event ProposerUpdated(address indexed proposer);
    event GuardianUpdated(address indexed guardian);

    error NotProposer();
    error NotGuardian();
    error ZeroAddress();
    error ZeroParameter();
    error WrongBond(uint256 expected, uint256 sent);
    error OutputNotFound(uint256 index);
    error NotMonotonic();
    error WrongStatus(OutputStatus status);
    error WindowClosed();
    error ResolutionExpired();
    error ResolutionStillOpen();
    error OutputAlreadyFinal();
    error OutputNotFinalizable();
    error NothingToWithdraw();
    error TransferFailed();

    modifier onlyProposer() {
        if (msg.sender != proposer) revert NotProposer();
        _;
    }

    modifier onlyGuardian() {
        if (msg.sender != guardian) revert NotGuardian();
        _;
    }

    constructor(
        address owner_,
        address proposer_,
        address guardian_,
        uint256 challengeWindow,
        uint256 resolutionPeriod,
        uint256 proposerBond,
        uint256 challengerBond
    ) Ownable(owner_) {
        if (proposer_ == address(0) || guardian_ == address(0)) revert ZeroAddress();
        if (challengeWindow == 0 || resolutionPeriod == 0 || proposerBond == 0 || challengerBond == 0) {
            revert ZeroParameter();
        }
        proposer = proposer_;
        guardian = guardian_;
        CHALLENGE_WINDOW = challengeWindow;
        RESOLUTION_PERIOD = resolutionPeriod;
        PROPOSER_BOND = proposerBond;
        CHALLENGER_BOND = challengerBond;
    }

    // ---------------------------------------------------------------------------------------------
    // Administration
    // ---------------------------------------------------------------------------------------------

    function setProposer(address proposer_) external onlyOwner {
        if (proposer_ == address(0)) revert ZeroAddress();
        proposer = proposer_;
        emit ProposerUpdated(proposer_);
    }

    /// @notice Replaces the guardian (the owner's remedy for an unresponsive or compromised guardian).
    function setGuardian(address guardian_) external onlyOwner {
        if (guardian_ == address(0)) revert ZeroAddress();
        guardian = guardian_;
        emit GuardianUpdated(guardian_);
    }

    // ---------------------------------------------------------------------------------------------
    // Proposing
    // ---------------------------------------------------------------------------------------------

    /// @notice Posts an output. Needs exactly `PROPOSER_BOND`. The L2 block number must strictly increase and
    ///         the withdrawal count must not shrink relative to the latest live (non-deleted) output.
    function proposeOutput(bytes32 withdrawalRoot, bytes32 l2BlockHash, uint64 l2BlockNumber, uint64 withdrawalCount)
        external
        payable
        onlyProposer
        returns (uint256 index)
    {
        if (msg.value != PROPOSER_BOND) revert WrongBond(PROPOSER_BOND, msg.value);
        (bool found, uint256 last) = latestLive();
        if (found) {
            Output storage prev = _outputs[last];
            if (l2BlockNumber <= prev.l2BlockNumber || withdrawalCount < prev.withdrawalCount) revert NotMonotonic();
        }
        index = _outputs.length;
        _outputs.push(
            Output({
                withdrawalRoot: withdrawalRoot,
                l2BlockHash: l2BlockHash,
                l2BlockNumber: l2BlockNumber,
                withdrawalCount: withdrawalCount,
                proposedAt: uint64(block.timestamp),
                status: OutputStatus.Proposed,
                proposer: msg.sender,
                challenger: address(0),
                challengedAt: 0,
                bondSettled: false
            })
        );
        emit OutputProposed(index, msg.sender, withdrawalRoot, l2BlockHash, l2BlockNumber, withdrawalCount);
    }

    /// @notice After an output survived its window (and nothing is under challenge) the proposer gets the bond back.
    function claimProposerBond(uint256 index) external {
        if (!isFinalizable(index)) revert OutputNotFinalizable();
        Output storage o = _outputs[index];
        if (o.bondSettled) revert NothingToWithdraw();
        o.bondSettled = true;
        credits[o.proposer] += PROPOSER_BOND;
        emit ProposerBondClaimed(index, o.proposer);
    }

    // ---------------------------------------------------------------------------------------------
    // Challenging
    // ---------------------------------------------------------------------------------------------

    /// @notice Disputes an output inside its window. Needs exactly `CHALLENGER_BOND`.
    function challengeOutput(uint256 index) external payable {
        Output storage o = _get(index);
        if (msg.value != CHALLENGER_BOND) revert WrongBond(CHALLENGER_BOND, msg.value);
        if (o.status != OutputStatus.Proposed) revert WrongStatus(o.status);
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp >= uint256(o.proposedAt) + CHALLENGE_WINDOW) revert WindowClosed();
        o.status = OutputStatus.Challenged;
        o.challenger = msg.sender;
        o.challengedAt = uint64(block.timestamp);
        ++openChallenges;
        emit OutputChallenged(index, msg.sender);
    }

    /// @notice Guardian verdict. If the proposer was wrong the output AND every later output are deleted and
    ///         the challenger receives both bonds. If the challenger was wrong the output goes back to
    ///         `Proposed` with a fresh window and the proposer receives the challenger's bond.
    function resolveChallenge(uint256 index, bool proposerWasWrong) external onlyGuardian {
        Output storage o = _get(index);
        if (o.status != OutputStatus.Challenged) revert WrongStatus(o.status);
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp > uint256(o.challengedAt) + RESOLUTION_PERIOD) revert ResolutionExpired();
        emit ChallengeResolved(index, proposerWasWrong);
        if (proposerWasWrong) {
            _deleteUpheld(index, o);
        } else {
            --openChallenges;
            credits[o.proposer] += CHALLENGER_BOND;
            o.status = OutputStatus.Proposed;
            o.challenger = address(0);
            o.challengedAt = 0;
            o.proposedAt = uint64(block.timestamp);
        }
    }

    /// @notice Anyone can expire a challenge the guardian left unresolved. Fail safe: the output (and all
    ///         later ones) are deleted and every bond is refunded; the proposer must propose again.
    function expireChallenge(uint256 index) external {
        Output storage o = _get(index);
        if (o.status != OutputStatus.Challenged) revert WrongStatus(o.status);
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp <= uint256(o.challengedAt) + RESOLUTION_PERIOD) revert ResolutionStillOpen();
        emit ChallengeExpired(index);
        _cascade(index);
    }

    /// @notice Emergency deletion by the guardian of `index` and every later output, before `index` is final.
    ///         Without a challenger bonds are refunded (the guardian cannot confiscate bonds on its own);
    ///         a challenged output is treated as an upheld challenge.
    function deleteFrom(uint256 index) external onlyGuardian {
        Output storage o = _get(index);
        if (o.status == OutputStatus.Deleted) revert WrongStatus(o.status);
        if (o.status == OutputStatus.Challenged) {
            _deleteUpheld(index, o);
        } else {
            // forge-lint: disable-next-line(block-timestamp)
            if (block.timestamp >= uint256(o.proposedAt) + CHALLENGE_WINDOW) revert OutputAlreadyFinal();
            _cascade(index);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pull payments
    // ---------------------------------------------------------------------------------------------

    function withdrawCredits() external nonReentrant {
        uint256 amount = credits[msg.sender];
        if (amount == 0) revert NothingToWithdraw();
        credits[msg.sender] = 0;
        emit CreditsWithdrawn(msg.sender, amount);
        (bool ok,) = msg.sender.call{value: amount}("");
        if (!ok) revert TransferFailed();
    }

    // ---------------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------------

    function outputCount() external view returns (uint256) {
        return _outputs.length;
    }

    function getOutput(uint256 index) external view returns (Output memory) {
        return _get(index);
    }

    /// @notice `Proposed`, window elapsed and no challenge open anywhere.
    function isFinalizable(uint256 index) public view returns (bool) {
        if (index >= _outputs.length) return false;
        Output storage o = _outputs[index];
        // forge-lint: disable-next-line(block-timestamp)
        bool windowElapsed = block.timestamp >= uint256(o.proposedAt) + CHALLENGE_WINDOW;
        return o.status == OutputStatus.Proposed && openChallenges == 0 && windowElapsed;
    }

    /// @notice Index of the newest output that is not deleted.
    function latestLive() public view returns (bool found, uint256 index) {
        uint256 i = _outputs.length;
        while (i > 0) {
            --i;
            if (_outputs[i].status != OutputStatus.Deleted) return (true, i);
        }
        return (false, 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    function _get(uint256 index) private view returns (Output storage) {
        if (index >= _outputs.length) revert OutputNotFound(index);
        return _outputs[index];
    }

    /// @dev Challenge upheld: challenger gets both bonds, output deleted, later outputs cascade.
    function _deleteUpheld(uint256 index, Output storage o) private {
        --openChallenges;
        o.bondSettled = true;
        credits[o.challenger] += PROPOSER_BOND + CHALLENGER_BOND;
        o.status = OutputStatus.Deleted;
        emit OutputDeleted(index);
        _cascade(index + 1);
    }

    /// @dev Deletes every non-deleted output from `from` on, refunding the bonds that are still held.
    ///      The loop is bounded by the outputs proposed since the last finalized one (proposer is a
    ///      trusted role; see THREAT-MODEL.md for the gas-griefing note).
    function _cascade(uint256 from) private {
        uint256 len = _outputs.length;
        for (uint256 i = from; i < len; ++i) {
            Output storage o = _outputs[i];
            if (o.status == OutputStatus.Deleted) continue;
            if (o.status == OutputStatus.Challenged) {
                --openChallenges;
                credits[o.challenger] += CHALLENGER_BOND;
            }
            if (!o.bondSettled) {
                o.bondSettled = true;
                credits[o.proposer] += PROPOSER_BOND;
            }
            o.status = OutputStatus.Deleted;
            emit OutputDeleted(i);
        }
    }
    // slither-disable-end timestamp
}
