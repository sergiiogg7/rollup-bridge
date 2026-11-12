// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {ToyVM, VMState} from "./ToyVM.sol";

/// @title BisectionGame
/// @notice An interactive dispute ("fault proof") game over a deterministic program, in the style of
///         Arbitrum's interactive fraud proofs and the Cannon/MIPS games of OP-style rollups, shrunk to a toy
///         machine (`ToyVM`) so the mechanism is readable end to end.
///
///         WHAT IS CLAIMED. A proposer bonds money and claims: "running the committed program for `steps`
///         steps from the state with hash `initialHash` ends in the state with hash `claimedHash`". Anybody
///         may dispute that claim by posting a bond. The two parties then play the game below.
///
///         THE GAME. Both agree on the state hash at step `lo` and disagree about step `hi` (initially
///         `0` and `steps`).
///           1. The proposer (on the clock) names the midpoint step `mid = lo + (hi - lo) / 2` and commits
///              to the state hash they claim there (`bisect`).
///           2. The challenger (on the clock) says whether they agree with that hash (`respond`). If they
///              agree the dispute is now in the upper half (`lo = mid`), otherwise in the lower half
///              (`hi = mid`, and the proposer's midpoint claim is now the claim at `hi`).
///           3. The interval halves every round. When it is a single step (`hi == lo + 1`), anybody can
///              call `step`, supplying the program and the state that hashes to `loHash`. The contract
///              executes ONE instruction itself and compares the result with the proposer's claim at `hi`:
///              equal means the proposer was right, different means the proposer lied.
///           4. Whoever misses a move deadline loses (`claimTimeout`).
///         The loser's bond goes to the winner. Money moves by pull payments (`credits`, `withdraw`).
///
///         WHY IT WORKS. The invariant "the state at `lo` is honest-agreed" is preserved by an honest
///         challenger (they only move `lo` up to a hash they verified) and an honest proposer (their midpoints
///         are true, so both halves keep a true end), therefore the contested single step is the one place
///         where a liar's claim and the real execution disagree, and the contract can settle it by running
///         one instruction. The number of on-chain moves is O(log steps), however long the computation.
///
///         WHAT IT DOES NOT DO. It proves statements about the committed ToyVM program only. It does not
///         verify any L2 state transition, and it is not wired to the bridge contracts (the guardian of
///         `OutputOracle` still decides challenges). It is a two-party game: see docs/DISPUTE-GAME.md,
///         "Limitations", for the sybil/collusion caveat and how production systems close it.
/// @dev Several games run in parallel inside one contract; each is independent (own bonds, own clock).
///      `step` is permissionless and `claimTimeout` is permissionless, so liveness never depends on one party.
contract BisectionGame is ReentrancyGuard {
    // Slither "timestamp" is silenced for the whole contract on purpose: the move clocks are defined in
    // block.timestamp and a validator can skew it by seconds, irrelevant for clocks of minutes or hours.
    // slither-disable-start timestamp

    /// @notice Where a game is. `AwaitingX` says whose move is next (or that anybody may move).
    enum Phase {
        None,
        AwaitingChallenge,
        AwaitingBisect,
        AwaitingResponse,
        AwaitingStep,
        Resolved
    }

    /// @notice How a resolved game ended.
    enum Resolution {
        None,
        Unchallenged,
        OneStepProof,
        Timeout
    }

    struct Game {
        address proposer;
        address challenger;
        Phase phase;
        Resolution resolution;
        bool proposerWon;
        uint64 lo;
        uint64 hi;
        uint64 deadline;
        bytes32 loHash;
        bytes32 hiHash;
        bytes32 midHash;
    }

    /// @notice Upper bound of the per-move clock; it keeps the deadline arithmetic far from uint64 limits.
    uint256 public constant MAX_MOVE_TIMEOUT = 365 days;

    /// @notice Hash of the program every game of this contract is about (the committed "L2 state transition").
    bytes32 public immutable PROGRAM_HASH;
    /// @notice Seconds each party has for each move (also the time anybody has to challenge a fresh claim).
    uint256 public immutable MOVE_TIMEOUT;
    uint256 public immutable PROPOSER_BOND;
    uint256 public immutable CHALLENGER_BOND;

    /// @notice Pull-payment balances (bonds returned or won).
    mapping(address account => uint256) public credits;

    Game[] private _games;

    event GameCreated(
        uint256 indexed id, address indexed proposer, bytes32 initialHash, uint64 steps, bytes32 claimedHash
    );
    event Challenged(uint256 indexed id, address indexed challenger);
    event Bisected(uint256 indexed id, uint64 step, bytes32 midHash);
    event Responded(uint256 indexed id, bool agree, uint64 lo, uint64 hi);
    event StepExecuted(uint256 indexed id, address indexed caller, bytes32 computedHash);
    event GameResolved(uint256 indexed id, bool proposerWon, Resolution resolution);
    event CreditsWithdrawn(address indexed account, uint256 amount);

    error ZeroParameter();
    error ParameterTooLarge();
    error UnknownGame(uint256 id);
    error WrongPhase(Phase phase);
    error WrongBond(uint256 expected, uint256 sent);
    error ZeroSteps();
    error NotProposer();
    error NotChallenger();
    error MoveTimeExpired();
    error TimeoutNotReached();
    error WrongStep(uint64 expected, uint64 got);
    error ProgramMismatch();
    error PreStateMismatch();
    error NothingToWithdraw();
    error TransferFailed();

    constructor(bytes32 programHash, uint256 moveTimeout, uint256 proposerBond, uint256 challengerBond) {
        if (programHash == bytes32(0) || moveTimeout == 0 || proposerBond == 0 || challengerBond == 0) {
            revert ZeroParameter();
        }
        if (moveTimeout > MAX_MOVE_TIMEOUT) revert ParameterTooLarge();
        PROGRAM_HASH = programHash;
        MOVE_TIMEOUT = moveTimeout;
        PROPOSER_BOND = proposerBond;
        CHALLENGER_BOND = challengerBond;
    }

    // ---------------------------------------------------------------------------------------------
    // Moves
    // ---------------------------------------------------------------------------------------------

    /// @notice Opens a game: claims that `steps` steps of the program from `initialHash` end at `claimedHash`.
    ///         Needs exactly `PROPOSER_BOND`. Anybody has `MOVE_TIMEOUT` to challenge.
    function createGame(bytes32 initialHash, uint64 steps, bytes32 claimedHash) external payable returns (uint256 id) {
        if (msg.value != PROPOSER_BOND) revert WrongBond(PROPOSER_BOND, msg.value);
        if (steps == 0) revert ZeroSteps();
        id = _games.length;
        Game storage g = _games.push(); // a struct this wide is assigned field by field (stack depth)
        g.proposer = msg.sender;
        g.phase = Phase.AwaitingChallenge;
        g.hi = steps;
        g.deadline = _newDeadline();
        g.loHash = initialHash;
        g.hiHash = claimedHash;
        emit GameCreated(id, msg.sender, initialHash, steps, claimedHash);
    }

    /// @notice Disputes the claim of game `id`. Needs exactly `CHALLENGER_BOND` and must come before the deadline.
    function challenge(uint256 id) external payable {
        Game storage g = _get(id);
        if (g.phase != Phase.AwaitingChallenge) revert WrongPhase(g.phase);
        if (msg.value != CHALLENGER_BOND) revert WrongBond(CHALLENGER_BOND, msg.value);
        _requireOnTime(g);
        g.challenger = msg.sender;
        g.phase = _phaseAfterNarrowing(g);
        g.deadline = _newDeadline();
        emit Challenged(id, msg.sender);
    }

    /// @notice Proposer move: commits to the state hash at the midpoint of the disputed interval.
    /// @param midStep Must equal `expectedStep(id)`; naming it makes the move explicit and stale moves fail.
    function bisect(uint256 id, uint64 midStep, bytes32 midHash) external {
        Game storage g = _get(id);
        if (g.phase != Phase.AwaitingBisect) revert WrongPhase(g.phase);
        if (msg.sender != g.proposer) revert NotProposer();
        _requireOnTime(g);
        uint64 expected = _mid(g);
        if (midStep != expected) revert WrongStep(expected, midStep);
        g.midHash = midHash;
        g.phase = Phase.AwaitingResponse;
        g.deadline = _newDeadline();
        emit Bisected(id, midStep, midHash);
    }

    /// @notice Challenger move: agrees (the dispute is in the upper half) or disagrees (lower half) with the
    ///         proposer's midpoint hash.
    function respond(uint256 id, bool agree) external {
        Game storage g = _get(id);
        if (g.phase != Phase.AwaitingResponse) revert WrongPhase(g.phase);
        if (msg.sender != g.challenger) revert NotChallenger();
        _requireOnTime(g);
        uint64 mid = _mid(g);
        if (agree) {
            g.lo = mid;
            g.loHash = g.midHash;
        } else {
            g.hi = mid;
            g.hiHash = g.midHash;
        }
        g.midHash = bytes32(0);
        g.phase = _phaseAfterNarrowing(g);
        g.deadline = _newDeadline();
        emit Responded(id, agree, g.lo, g.hi);
    }

    /// @notice Settles a single-step dispute by executing the instruction on-chain. Anybody may call it, with
    ///         the full program (checked against `PROGRAM_HASH`) and the state that hashes to the agreed `loHash`.
    ///         The proposer wins if executing that state yields exactly the hash they claimed at `hi`.
    function step(uint256 id, bytes calldata program, VMState calldata preState) external {
        Game storage g = _get(id);
        if (g.phase != Phase.AwaitingStep) revert WrongPhase(g.phase);
        _requireOnTime(g);
        if (keccak256(program) != PROGRAM_HASH) revert ProgramMismatch();
        if (ToyVM.hashState(preState) != g.loHash) revert PreStateMismatch();
        bytes32 computed = ToyVM.hashState(ToyVM.step(program, preState));
        emit StepExecuted(id, msg.sender, computed);
        _settle(id, g, computed == g.hiHash, Resolution.OneStepProof);
    }

    /// @notice Anybody can end a game whose current move deadline has passed. The party that was on the clock
    ///         loses; an unchallenged claim stands (the proposer wins by default and gets the bond back).
    function claimTimeout(uint256 id) external {
        Game storage g = _get(id);
        Phase phase = g.phase;
        if (phase == Phase.Resolved) revert WrongPhase(phase);
        if (block.timestamp <= g.deadline) revert TimeoutNotReached();
        // Only the proposer's own bisection move can time out against the proposer; every other waiting
        // phase (no challenger, no response, no one-step proof) is the challenging side's burden.
        bool proposerWon = phase != Phase.AwaitingBisect;
        _settle(id, g, proposerWon, phase == Phase.AwaitingChallenge ? Resolution.Unchallenged : Resolution.Timeout);
    }

    /// @notice Withdraws the caller's accumulated bond credits.
    function withdraw() external nonReentrant {
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

    function gameCount() external view returns (uint256) {
        return _games.length;
    }

    function getGame(uint256 id) external view returns (Game memory) {
        return _get(id);
    }

    /// @notice The step number the proposer's next `bisect` must name for game `id`.
    function expectedStep(uint256 id) external view returns (uint64) {
        return _mid(_get(id));
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    function _get(uint256 id) private view returns (Game storage) {
        if (id >= _games.length) revert UnknownGame(id);
        return _games[id];
    }

    function _mid(Game storage g) private view returns (uint64) {
        return g.lo + (g.hi - g.lo) / 2;
    }

    /// @dev After the interval changed: a single step left means anybody can execute it, else bisect again.
    function _phaseAfterNarrowing(Game storage g) private view returns (Phase) {
        return g.hi - g.lo == 1 ? Phase.AwaitingStep : Phase.AwaitingBisect;
    }

    function _newDeadline() private view returns (uint64) {
        // MOVE_TIMEOUT <= 365 days (constructor), so the sum fits in uint64 for any realistic timestamp.
        // forge-lint: disable-next-line(unsafe-typecast)
        return uint64(block.timestamp + MOVE_TIMEOUT);
    }

    function _requireOnTime(Game storage g) private view {
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp > g.deadline) revert MoveTimeExpired();
    }

    /// @dev Ends the game and credits the winner. An unchallenged game only holds the proposer's bond.
    function _settle(uint256 id, Game storage g, bool proposerWon, Resolution resolution) private {
        g.phase = Phase.Resolved;
        g.resolution = resolution;
        g.proposerWon = proposerWon;
        if (g.challenger == address(0)) {
            credits[g.proposer] += PROPOSER_BOND;
        } else if (proposerWon) {
            credits[g.proposer] += PROPOSER_BOND + CHALLENGER_BOND;
        } else {
            credits[g.challenger] += PROPOSER_BOND + CHALLENGER_BOND;
        }
        emit GameResolved(id, proposerWon, resolution);
    }
    // slither-disable-end timestamp
}
