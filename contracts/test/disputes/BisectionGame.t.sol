// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test} from "forge-std/Test.sol";
import {BisectionGame} from "../../src/disputes/BisectionGame.sol";
import {ToyVM, VMState} from "../../src/disputes/ToyVM.sol";
import {Asm} from "./utils/Asm.sol";

/// @notice Withdrawal receiver for the pull-payment tests: can reject ETH or try to re-enter `withdraw`.
contract GameReceiver {
    BisectionGame public immutable GAME;
    bool public rejecting;
    bool public reenter;
    uint256 public blockedReentries;

    constructor(BisectionGame game) {
        GAME = game;
    }

    function set(bool rejecting_, bool reenter_) external {
        rejecting = rejecting_;
        reenter = reenter_;
    }

    function callWithdraw() external {
        GAME.withdraw();
    }

    function createGame(bytes32 initialHash, uint64 steps, bytes32 claim) external payable returns (uint256) {
        return GAME.createGame{value: msg.value}(initialHash, steps, claim);
    }

    receive() external payable {
        require(!rejecting, "rejecting");
        if (reenter) {
            try GAME.withdraw() {}
            catch {
                ++blockedReentries;
            }
        }
    }
}

/// @notice Shared set-up: a game contract over a program that runs for hundreds of steps, plus the TRUE trace so a
///         test can play an honest side (or a precise lie) without re-deriving anything.
abstract contract GameFixture is Test {
    uint256 internal constant TIMEOUT = 1 hours;
    uint256 internal constant PROPOSER_BOND = 1 ether;
    uint256 internal constant CHALLENGER_BOND = 0.5 ether;
    uint256 internal constant MAX_STEPS = 256;

    BisectionGame internal game;
    bytes internal program;
    VMState internal init;
    bytes32[] internal truth; // truth[i] = hash of the true state after i steps
    VMState[] internal truthStates;

    address internal proposer = makeAddr("proposer");
    address internal challenger = makeAddr("challenger");
    address internal bystander = makeAddr("bystander");

    function setUp() public virtual {
        program = Asm.sumLoop(200); // 3 + 3 * 200 = 603 steps before HALT: never halted inside MAX_STEPS
        init = Asm.zeroState();
        game = new BisectionGame(keccak256(program), TIMEOUT, PROPOSER_BOND, CHALLENGER_BOND);
        VMState[] memory t = Asm.trace(program, init, MAX_STEPS);
        for (uint256 i = 0; i < t.length; ++i) {
            truthStates.push(t[i]);
            truth.push(ToyVM.hashState(t[i]));
        }
        vm.deal(proposer, 100 ether);
        vm.deal(challenger, 100 ether);
        vm.deal(bystander, 100 ether);
    }

    // ----- moves as each party -----

    function _create(uint64 steps, bytes32 claim) internal returns (uint256 id) {
        vm.prank(proposer);
        id = game.createGame{value: PROPOSER_BOND}(truth[0], steps, claim);
    }

    function _challenge(uint256 id) internal {
        vm.prank(challenger);
        game.challenge{value: CHALLENGER_BOND}(id);
    }

    function _bisect(uint256 id, uint64 step, bytes32 h) internal {
        vm.prank(proposer);
        game.bisect(id, step, h);
    }

    function _respond(uint256 id, bool agree) internal {
        vm.prank(challenger);
        game.respond(id, agree);
    }

    function _step(uint256 id, address caller, VMState memory pre) internal {
        vm.prank(caller);
        game.step(id, program, pre);
    }

    function _game(uint256 id) internal view returns (BisectionGame.Game memory) {
        return game.getGame(id);
    }

    function _phase(uint256 id) internal view returns (BisectionGame.Phase) {
        return game.getGame(id).phase;
    }

    /// @dev A lie: the claimed hash at every step >= `from` is wrong (the claims before it are true).
    function _liarClaims(uint64 steps, uint64 from) internal view returns (bytes32[] memory c) {
        c = new bytes32[](steps + 1);
        for (uint256 i = 0; i <= steps; ++i) {
            c[i] = i >= from ? keccak256(abi.encode(truth[i], "lie")) : truth[i];
        }
    }

    function _truthClaims(uint64 steps) internal view returns (bytes32[] memory c) {
        c = new bytes32[](steps + 1);
        for (uint256 i = 0; i <= steps; ++i) {
            c[i] = truth[i];
        }
    }

    /// @dev Plays game `id` to the end: the proposer posts `claimed[mid]`, the challenger answers honestly
    ///      (agrees iff the hash is the true one) or pseudo-randomly, whoever is right submits the one-step proof.
    ///      Returns the number of moves played here (bisect + respond + step), without `create` and `challenge`.
    function _play(uint256 id, bytes32[] memory claimed, bool honestChallenger, uint256 seed)
        internal
        returns (uint256 moves)
    {
        for (uint256 guard = 0; guard < 200; ++guard) {
            BisectionGame.Game memory g = _game(id);
            if (g.phase == BisectionGame.Phase.AwaitingBisect) {
                uint64 mid = game.expectedStep(id);
                _bisect(id, mid, claimed[mid]);
            } else if (g.phase == BisectionGame.Phase.AwaitingResponse) {
                uint64 mid = game.expectedStep(id);
                bool agree =
                    honestChallenger ? g.midHash == truth[mid] : uint256(keccak256(abi.encode(seed, guard))) % 2 == 0;
                _respond(id, agree);
            } else if (g.phase == BisectionGame.Phase.AwaitingStep) {
                _step(id, bystander, truthStates[g.lo]);
            } else {
                return moves;
            }
            ++moves;
        }
        revert("game did not end");
    }

    function _ceilLog2(uint256 x) internal pure returns (uint256 n) {
        while ((uint256(1) << n) < x) ++n;
    }
}

contract BisectionGameTest is GameFixture {
    // ---------------------------------------------------------------------------------------------
    // Construction and views
    // ---------------------------------------------------------------------------------------------

    function test_constructor_storesParameters() public view {
        assertEq(game.PROGRAM_HASH(), keccak256(program));
        assertEq(game.MOVE_TIMEOUT(), TIMEOUT);
        assertEq(game.PROPOSER_BOND(), PROPOSER_BOND);
        assertEq(game.CHALLENGER_BOND(), CHALLENGER_BOND);
    }

    function test_RevertWhen_constructorGetsAZeroParameter() public {
        vm.expectRevert(BisectionGame.ZeroParameter.selector);
        new BisectionGame(bytes32(0), TIMEOUT, 1, 1);
        vm.expectRevert(BisectionGame.ZeroParameter.selector);
        new BisectionGame(keccak256(program), 0, 1, 1);
        vm.expectRevert(BisectionGame.ZeroParameter.selector);
        new BisectionGame(keccak256(program), TIMEOUT, 0, 1);
        vm.expectRevert(BisectionGame.ZeroParameter.selector);
        new BisectionGame(keccak256(program), TIMEOUT, 1, 0);
    }

    function test_RevertWhen_constructorTimeoutIsTooLong() public {
        vm.expectRevert(BisectionGame.ParameterTooLarge.selector);
        new BisectionGame(keccak256(program), 366 days, 1, 1);
    }

    function test_RevertWhen_gameIsUnknown() public {
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.UnknownGame.selector, 0));
        game.getGame(0);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.UnknownGame.selector, 7));
        game.expectedStep(7);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.UnknownGame.selector, 3));
        game.claimTimeout(3);
    }

    // ---------------------------------------------------------------------------------------------
    // createGame / challenge
    // ---------------------------------------------------------------------------------------------

    function test_createGame_storesTheClaimAndEscrowsTheBond() public {
        vm.expectEmit(true, true, false, true, address(game));
        emit BisectionGame.GameCreated(0, proposer, truth[0], 40, truth[40]);
        uint256 id = _create(40, truth[40]);
        assertEq(id, 0);
        assertEq(game.gameCount(), 1);
        BisectionGame.Game memory g = _game(id);
        assertEq(g.proposer, proposer);
        assertEq(g.challenger, address(0));
        assertTrue(g.phase == BisectionGame.Phase.AwaitingChallenge);
        assertEq(g.lo, 0);
        assertEq(g.hi, 40);
        assertEq(g.loHash, truth[0]);
        assertEq(g.hiHash, truth[40]);
        assertEq(g.deadline, block.timestamp + TIMEOUT);
        assertEq(address(game).balance, PROPOSER_BOND);
    }

    function test_RevertWhen_createGameWithWrongBond() public {
        vm.prank(proposer);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongBond.selector, PROPOSER_BOND, PROPOSER_BOND - 1));
        game.createGame{value: PROPOSER_BOND - 1}(truth[0], 10, truth[10]);
    }

    function test_RevertWhen_createGameWithZeroSteps() public {
        vm.prank(proposer);
        vm.expectRevert(BisectionGame.ZeroSteps.selector);
        game.createGame{value: PROPOSER_BOND}(truth[0], 0, truth[0]);
    }

    function test_challenge_movesToBisectAndStartsTheClock() public {
        uint256 id = _create(40, truth[40]);
        skip(TIMEOUT / 2);
        vm.expectEmit(true, true, false, false, address(game));
        emit BisectionGame.Challenged(id, challenger);
        _challenge(id);
        BisectionGame.Game memory g = _game(id);
        assertEq(g.challenger, challenger);
        assertTrue(g.phase == BisectionGame.Phase.AwaitingBisect);
        assertEq(g.deadline, block.timestamp + TIMEOUT);
        assertEq(address(game).balance, PROPOSER_BOND + CHALLENGER_BOND);
    }

    function test_RevertWhen_challengeWithWrongBond() public {
        uint256 id = _create(40, truth[40]);
        vm.prank(challenger);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongBond.selector, CHALLENGER_BOND, 0));
        game.challenge(id);
    }

    function test_RevertWhen_challengeAfterTheDeadline() public {
        uint256 id = _create(40, truth[40]);
        skip(TIMEOUT + 1);
        vm.prank(challenger);
        vm.expectRevert(BisectionGame.MoveTimeExpired.selector);
        game.challenge{value: CHALLENGER_BOND}(id);
    }

    function test_RevertWhen_challengeIsPlacedTwice() public {
        uint256 id = _create(40, truth[40]);
        _challenge(id);
        vm.prank(bystander);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.AwaitingBisect));
        game.challenge{value: CHALLENGER_BOND}(id);
    }

    function test_RevertWhen_challengingAnUnknownGame() public {
        vm.prank(challenger);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.UnknownGame.selector, 0));
        game.challenge{value: CHALLENGER_BOND}(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Bisection mechanics
    // ---------------------------------------------------------------------------------------------

    function test_bisect_namesTheMidpointAndPassesTheTurn() public {
        uint256 id = _create(40, truth[40]);
        _challenge(id);
        assertEq(game.expectedStep(id), 20);
        vm.expectEmit(true, false, false, true, address(game));
        emit BisectionGame.Bisected(id, 20, truth[20]);
        _bisect(id, 20, truth[20]);
        BisectionGame.Game memory g = _game(id);
        assertTrue(g.phase == BisectionGame.Phase.AwaitingResponse);
        assertEq(g.midHash, truth[20]);
        assertEq(g.deadline, block.timestamp + TIMEOUT);
    }

    function test_respond_agreeMovesTheLowerBoundUp() public {
        uint256 id = _create(40, truth[40]);
        _challenge(id);
        _bisect(id, 20, truth[20]);
        vm.expectEmit(true, false, false, true, address(game));
        emit BisectionGame.Responded(id, true, 20, 40);
        _respond(id, true);
        BisectionGame.Game memory g = _game(id);
        assertEq(g.lo, 20);
        assertEq(g.hi, 40);
        assertEq(g.loHash, truth[20]);
        assertEq(g.hiHash, truth[40]);
        assertEq(g.midHash, bytes32(0));
        assertTrue(g.phase == BisectionGame.Phase.AwaitingBisect);
    }

    function test_respond_disagreeMovesTheUpperBoundDown() public {
        uint256 id = _create(40, truth[40]);
        _challenge(id);
        bytes32 lie = keccak256("lie");
        _bisect(id, 20, lie);
        _respond(id, false);
        BisectionGame.Game memory g = _game(id);
        assertEq(g.lo, 0);
        assertEq(g.hi, 20);
        assertEq(g.loHash, truth[0]);
        assertEq(g.hiHash, lie);
        assertTrue(g.phase == BisectionGame.Phase.AwaitingBisect);
    }

    function test_midpoints_ofAnOddIntervalRoundDown() public {
        uint256 id = _create(7, truth[7]);
        _challenge(id);
        assertEq(game.expectedStep(id), 3); // (0, 7) -> 3
        _bisect(id, 3, truth[3]);
        _respond(id, true); // (3, 7)
        assertEq(game.expectedStep(id), 5);
        _bisect(id, 5, truth[5]);
        _respond(id, false); // (3, 5)
        assertEq(game.expectedStep(id), 4);
        _bisect(id, 4, truth[4]);
        _respond(id, true); // (4, 5): one step left
        assertTrue(_phase(id) == BisectionGame.Phase.AwaitingStep);
    }

    function test_singleStepGameGoesStraightToTheOneStepProof() public {
        uint256 id = _create(1, truth[1]);
        _challenge(id);
        assertTrue(_phase(id) == BisectionGame.Phase.AwaitingStep);
        vm.expectEmit(true, true, false, true, address(game));
        emit BisectionGame.StepExecuted(id, challenger, truth[1]);
        vm.expectEmit(true, false, false, true, address(game));
        emit BisectionGame.GameResolved(id, true, BisectionGame.Resolution.OneStepProof);
        _step(id, challenger, truthStates[0]);
        BisectionGame.Game memory g = _game(id);
        assertTrue(g.phase == BisectionGame.Phase.Resolved);
        assertTrue(g.proposerWon); // the claim at step 1 is the true one
    }

    function test_twoStepGameNeedsOneBisection() public {
        bytes32[] memory claimed = _liarClaims(2, 2);
        uint256 id = _create(2, claimed[2]);
        _challenge(id);
        uint256 moves = _play(id, claimed, true, 0);
        assertEq(moves, 3); // bisect, respond, step
        assertFalse(_game(id).proposerWon);
    }

    // ---------------------------------------------------------------------------------------------
    // Who wins
    // ---------------------------------------------------------------------------------------------

    function test_liarLosesToTheHonestChallenger_andIsSlashed() public {
        bytes32[] memory claimed = _liarClaims(40, 17);
        uint256 id = _create(40, claimed[40]);
        _challenge(id);

        _play(id, claimed, true, 0);

        BisectionGame.Game memory g = _game(id);
        assertTrue(g.phase == BisectionGame.Phase.Resolved);
        assertTrue(g.resolution == BisectionGame.Resolution.OneStepProof);
        assertFalse(g.proposerWon);
        assertEq(g.lo, 16); // the first corrupted step is 17: the contested transition is 16 -> 17
        assertEq(g.hi, 17);
        assertEq(game.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(game.credits(proposer), 0);

        uint256 before = challenger.balance;
        vm.prank(challenger);
        game.withdraw();
        assertEq(challenger.balance - before, PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(address(game).balance, 0);
    }

    function test_honestProposerBeatsTheLyingChallenger_andCollectsTheirBond() public {
        uint256 id = _create(40, truth[40]);
        _challenge(id);
        // a challenger who disputes everything: always disagrees
        for (uint256 guard = 0; guard < 20 && _phase(id) != BisectionGame.Phase.AwaitingStep; ++guard) {
            uint64 mid = game.expectedStep(id);
            _bisect(id, mid, truth[mid]);
            _respond(id, false);
        }
        _step(id, proposer, truthStates[_game(id).lo]);
        BisectionGame.Game memory g = _game(id);
        assertTrue(g.proposerWon);
        assertEq(game.credits(proposer), PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(game.credits(challenger), 0);
    }

    function test_theLastStepOfTheTraceCanBeTheLie() public {
        bytes32[] memory claimed = _liarClaims(33, 33);
        uint256 id = _create(33, claimed[33]);
        _challenge(id);
        _play(id, claimed, true, 0);
        BisectionGame.Game memory g = _game(id);
        assertEq(g.lo, 32);
        assertEq(g.hi, 33);
        assertFalse(g.proposerWon);
    }

    function test_theFirstStepCanBeTheLie() public {
        bytes32[] memory claimed = _liarClaims(33, 1);
        uint256 id = _create(33, claimed[33]);
        _challenge(id);
        _play(id, claimed, true, 0);
        BisectionGame.Game memory g = _game(id);
        assertEq(g.lo, 0);
        assertEq(g.hi, 1);
        assertFalse(g.proposerWon);
    }

    /// @dev The number of on-chain moves is O(log steps): create + challenge + (bisect + respond) per halving +
    ///      one step proof, with at most ceil(log2 steps) halvings.
    function test_gameCostsLogarithmicallyManyMoves() public {
        uint64[8] memory sizes = [uint64(1), 2, 3, 7, 8, 33, 100, 256];
        for (uint256 s = 0; s < sizes.length; ++s) {
            uint64 n = sizes[s];
            bytes32[] memory claimed = _liarClaims(n, n / 2 + 1);
            uint256 id = _create(n, claimed[n]);
            _challenge(id);
            uint256 moves = 2 + _play(id, claimed, true, 0);
            assertLe(moves, 2 * _ceilLog2(n) + 3, "moves exceed 2*ceil(log2 N)+3");
            assertFalse(_game(id).proposerWon);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Adversarial table: each attack is one named test
    // ---------------------------------------------------------------------------------------------

    function _challenged(uint64 steps) internal returns (uint256 id) {
        id = _create(steps, truth[steps]);
        _challenge(id);
    }

    function test_RevertWhen_challengerBisectsOutOfTurn() public {
        uint256 id = _challenged(40);
        vm.prank(challenger);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.NotProposer.selector));
        game.bisect(id, 20, truth[20]);
    }

    function test_RevertWhen_thirdPartyBisects() public {
        uint256 id = _challenged(40);
        vm.prank(bystander);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.NotProposer.selector));
        game.bisect(id, 20, truth[20]);
    }

    function test_RevertWhen_proposerRespondsOutOfTurn() public {
        uint256 id = _challenged(40);
        _bisect(id, 20, truth[20]);
        vm.prank(proposer);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.NotChallenger.selector));
        game.respond(id, true);
    }

    function test_RevertWhen_thirdPartyResponds() public {
        uint256 id = _challenged(40);
        _bisect(id, 20, truth[20]);
        vm.prank(bystander);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.NotChallenger.selector));
        game.respond(id, true);
    }

    function test_RevertWhen_respondingBeforeTheProposerBisected() public {
        uint256 id = _challenged(40);
        vm.prank(challenger);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.AwaitingBisect));
        game.respond(id, true);
    }

    function test_RevertWhen_bisectingTwiceInARow() public {
        uint256 id = _challenged(40);
        _bisect(id, 20, truth[20]);
        vm.prank(proposer);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.AwaitingResponse));
        game.bisect(id, 20, truth[20]);
    }

    function test_RevertWhen_bisectingTheWrongStep() public {
        uint256 id = _challenged(40);
        vm.startPrank(proposer);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongStep.selector, 20, 19));
        game.bisect(id, 19, truth[19]);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongStep.selector, 20, 40)); // the upper bound itself
        game.bisect(id, 40, truth[40]);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongStep.selector, 20, 0)); // the lower bound itself
        game.bisect(id, 0, truth[0]);
        vm.stopPrank();
    }

    function test_RevertWhen_bisectingOutsideTheCurrentInterval() public {
        uint256 id = _challenged(40);
        _bisect(id, 20, truth[20]);
        _respond(id, true); // interval is now (20, 40): a bisection of the old lower half is out of bounds
        vm.prank(proposer);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongStep.selector, 30, 10));
        game.bisect(id, 10, truth[10]);
    }

    function test_RevertWhen_bisectingAnUnchallengedGame() public {
        uint256 id = _create(40, truth[40]);
        vm.prank(proposer);
        vm.expectRevert(
            abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.AwaitingChallenge)
        );
        game.bisect(id, 20, truth[20]);
    }

    function test_RevertWhen_bisectingAfterTheDeadline() public {
        uint256 id = _challenged(40);
        skip(TIMEOUT + 1);
        vm.prank(proposer);
        vm.expectRevert(BisectionGame.MoveTimeExpired.selector);
        game.bisect(id, 20, truth[20]);
    }

    function test_RevertWhen_respondingAfterTheDeadline() public {
        uint256 id = _challenged(40);
        _bisect(id, 20, truth[20]);
        skip(TIMEOUT + 1);
        vm.prank(challenger);
        vm.expectRevert(BisectionGame.MoveTimeExpired.selector);
        game.respond(id, true);
    }

    function test_RevertWhen_provingTheStepAfterTheDeadline() public {
        uint256 id = _challenged(1);
        skip(TIMEOUT + 1);
        vm.expectRevert(BisectionGame.MoveTimeExpired.selector);
        game.step(id, program, truthStates[0]);
    }

    function test_RevertWhen_stepIsProvedWhileBisectionIsStillRunning() public {
        uint256 id = _challenged(40);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.AwaitingBisect));
        game.step(id, program, truthStates[0]);
    }

    function test_RevertWhen_stepIsProvedOnAnUnchallengedGame() public {
        uint256 id = _create(1, truth[1]);
        vm.expectRevert(
            abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.AwaitingChallenge)
        );
        game.step(id, program, truthStates[0]);
    }

    /// @dev One-step proof with a pre-state that does not hash to the agreed `loHash`: the contract refuses.
    function test_RevertWhen_oneStepProofUsesAWrongPreState() public {
        uint256 id = _challenged(1);
        VMState memory wrong = truthStates[0];
        wrong.regs[3] = 12345;
        vm.expectRevert(BisectionGame.PreStateMismatch.selector);
        game.step(id, program, wrong);
    }

    /// @dev The pre-state of ANOTHER step (a true state, just not the contested one) is refused too.
    function test_RevertWhen_oneStepProofUsesTheStateOfAnotherStep() public {
        uint256 id = _challenged(1);
        vm.expectRevert(BisectionGame.PreStateMismatch.selector);
        game.step(id, program, truthStates[1]);
    }

    function test_RevertWhen_oneStepProofUsesAnotherProgram() public {
        uint256 id = _challenged(1);
        vm.expectRevert(BisectionGame.ProgramMismatch.selector);
        game.step(id, Asm.sumLoop(201), truthStates[0]);
    }

    function test_oneStepProofIsPermissionless() public {
        bytes32[] memory claimed = _liarClaims(1, 1);
        uint256 id = _create(1, claimed[1]);
        _challenge(id);
        _step(id, bystander, truthStates[0]); // neither party sends the proof
        assertFalse(_game(id).proposerWon);
    }

    function test_RevertWhen_oneStepProofIsSubmittedTwice() public {
        uint256 id = _challenged(1);
        _step(id, challenger, truthStates[0]);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.Resolved));
        game.step(id, program, truthStates[0]);
    }

    function test_RevertWhen_movesAreMadeInAResolvedGame() public {
        bytes32[] memory claimed = _liarClaims(1, 1);
        uint256 id = _create(1, claimed[1]);
        _challenge(id);
        _step(id, challenger, truthStates[0]);
        vm.startPrank(proposer);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.Resolved));
        game.bisect(id, 0, bytes32(0));
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.Resolved));
        game.respond(id, true);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.Resolved));
        game.challenge(id);
        vm.stopPrank();
    }

    // ----- clocks -----

    function test_RevertWhen_timeoutIsClaimedBeforeTheDeadline() public {
        uint256 id = _challenged(40);
        vm.expectRevert(BisectionGame.TimeoutNotReached.selector);
        game.claimTimeout(id);
        skip(TIMEOUT); // exactly at the deadline the move is still on time
        vm.expectRevert(BisectionGame.TimeoutNotReached.selector);
        game.claimTimeout(id);
    }

    function test_timeout_unchallengedClaimStandsAndTheBondComesBack() public {
        uint256 id = _create(40, truth[40]);
        skip(TIMEOUT + 1);
        vm.expectEmit(true, false, false, true, address(game));
        emit BisectionGame.GameResolved(id, true, BisectionGame.Resolution.Unchallenged);
        game.claimTimeout(id);
        BisectionGame.Game memory g = _game(id);
        assertTrue(g.phase == BisectionGame.Phase.Resolved);
        assertTrue(g.resolution == BisectionGame.Resolution.Unchallenged);
        assertTrue(g.proposerWon);
        assertEq(game.credits(proposer), PROPOSER_BOND);
        vm.prank(proposer);
        game.withdraw();
        assertEq(address(game).balance, 0);
    }

    function test_timeout_silentProposerLosesItsBisectionMove() public {
        uint256 id = _challenged(40);
        skip(TIMEOUT + 1);
        vm.prank(bystander);
        game.claimTimeout(id);
        BisectionGame.Game memory g = _game(id);
        assertFalse(g.proposerWon);
        assertTrue(g.resolution == BisectionGame.Resolution.Timeout);
        assertEq(game.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND);
    }

    function test_timeout_silentChallengerLosesItsResponse() public {
        uint256 id = _challenged(40);
        _bisect(id, 20, truth[20]);
        skip(TIMEOUT + 1);
        game.claimTimeout(id);
        BisectionGame.Game memory g = _game(id);
        assertTrue(g.proposerWon);
        assertTrue(g.resolution == BisectionGame.Resolution.Timeout);
        assertEq(game.credits(proposer), PROPOSER_BOND + CHALLENGER_BOND);
    }

    function test_timeout_challengerWhoNeverProvesTheStepLoses() public {
        uint256 id = _challenged(1);
        skip(TIMEOUT + 1);
        game.claimTimeout(id);
        assertTrue(_game(id).proposerWon);
        assertEq(game.credits(proposer), PROPOSER_BOND + CHALLENGER_BOND);
    }

    function test_RevertWhen_timeoutIsClaimedTwice() public {
        uint256 id = _challenged(40);
        skip(TIMEOUT + 1);
        game.claimTimeout(id);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.Resolved));
        game.claimTimeout(id);
        assertEq(game.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND); // paid once
    }

    function test_RevertWhen_aResolvedGameIsResolvedAgainByTimeout() public {
        bytes32[] memory claimed = _liarClaims(1, 1);
        uint256 id = _create(1, claimed[1]);
        _challenge(id);
        _step(id, challenger, truthStates[0]);
        skip(TIMEOUT + 1);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.Resolved));
        game.claimTimeout(id);
        assertFalse(_game(id).proposerWon); // the verdict did not flip
    }

    function test_everyMoveResetsTheClock() public {
        uint256 id = _challenged(40);
        skip(TIMEOUT - 1);
        _bisect(id, 20, truth[20]);
        skip(TIMEOUT - 1);
        _respond(id, true); // still on time: the clock restarted at the previous move
        assertTrue(_phase(id) == BisectionGame.Phase.AwaitingBisect);
    }

    // ----- parallel games -----

    function test_parallelGamesAreIndependent() public {
        bytes32[] memory lie = _liarClaims(40, 9);
        uint256 a = _create(40, lie[40]); // a lie
        uint256 b = _create(40, truth[40]); // the truth
        uint256 c = _create(12, truth[12]); // never challenged
        assertEq(address(game).balance, 3 * PROPOSER_BOND);
        _challenge(a);
        _challenge(b);

        // interleave moves of the two disputes
        _bisect(a, 20, lie[20]);
        _bisect(b, 20, truth[20]);
        _respond(b, false); // challenger lies in b: disputes a true midpoint
        _respond(a, false); // and rightly disputes the false one in a (the lie started at step 9)
        assertEq(_game(a).hi, 20);
        assertEq(_game(b).hi, 20);
        assertEq(_game(c).lo, 0);

        _play(a, lie, true, 0);
        _play(b, _truthClaims(40), false, 1);
        skip(TIMEOUT + 1);
        game.claimTimeout(c);

        assertFalse(_game(a).proposerWon);
        assertTrue(_game(b).proposerWon);
        assertTrue(_game(c).proposerWon);
        // a: challenger wins 1.5; b: proposer wins 1.5; c: proposer gets 1.0 back; the proposer is the same
        // address in all three, so it holds (b) + (c); the challenger holds (a).
        assertEq(game.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(game.credits(proposer), PROPOSER_BOND + CHALLENGER_BOND + PROPOSER_BOND);
        assertEq(address(game).balance, game.credits(challenger) + game.credits(proposer));
    }

    // ----- pull payments -----

    function test_RevertWhen_withdrawingWithoutCredit() public {
        vm.prank(bystander);
        vm.expectRevert(BisectionGame.NothingToWithdraw.selector);
        game.withdraw();
    }

    function test_RevertWhen_creditIsWithdrawnTwice() public {
        uint256 id = _create(5, truth[5]);
        skip(TIMEOUT + 1);
        game.claimTimeout(id);
        vm.startPrank(proposer);
        game.withdraw();
        vm.expectRevert(BisectionGame.NothingToWithdraw.selector);
        game.withdraw();
        vm.stopPrank();
    }

    function test_withdraw_aRejectingReceiverKeepsItsCreditAndBlocksNobody() public {
        GameReceiver r = new GameReceiver(game);
        vm.deal(address(r), 5 ether);
        r.set(true, false);
        uint256 id = r.createGame{value: PROPOSER_BOND}(truth[0], 5, truth[5]);
        skip(TIMEOUT + 1);
        game.claimTimeout(id);
        assertEq(game.credits(address(r)), PROPOSER_BOND);

        vm.expectRevert(BisectionGame.TransferFailed.selector);
        r.callWithdraw();
        assertEq(game.credits(address(r)), PROPOSER_BOND); // the credit survived the failed payout

        // other users are unaffected
        uint256 id2 = _create(5, truth[5]);
        skip(TIMEOUT + 1);
        game.claimTimeout(id2);
        vm.prank(proposer);
        game.withdraw();

        r.set(false, false);
        r.callWithdraw();
        assertEq(game.credits(address(r)), 0);
    }

    function test_withdraw_reentrancyIsBlockedAndPaysOnce() public {
        GameReceiver r = new GameReceiver(game);
        vm.deal(address(r), 5 ether);
        r.set(false, true);
        uint256 id = r.createGame{value: PROPOSER_BOND}(truth[0], 5, truth[5]);
        // keep a second balance in the contract that a re-entrant double payout would steal
        _create(5, truth[5]);
        skip(TIMEOUT + 1);
        game.claimTimeout(id);

        uint256 before = address(r).balance;
        r.callWithdraw();
        assertEq(address(r).balance - before, PROPOSER_BOND);
        assertEq(r.blockedReentries(), 1);
        assertEq(address(game).balance, PROPOSER_BOND); // the other game's bond is untouched
    }

    // ----- a documented limitation, as an executable test -----

    /// @dev KNOWN LIMITATION (docs/DISPUTE-GAME.md, "Limitations"): this is a TWO-party game. A proposer who lies
    ///      can open the game, challenge it from a second address of its own and let that "challenger" run out
    ///      of time: the false claim then stands, and no honest observer could take the challenger seat.
    ///      Production systems close this with many-party games (every claim can be countered by anybody).
    function test_Limitation_aSybilChallengerLetsALieStand() public {
        bytes32[] memory lie = _liarClaims(40, 5);
        uint256 id = _create(40, lie[40]);
        address sybil = makeAddr("sybil");
        vm.deal(sybil, 10 ether);
        vm.prank(sybil);
        game.challenge{value: CHALLENGER_BOND}(id); // the honest challenger arrives too late: the seat is taken
        _bisect(id, 20, lie[20]);
        skip(TIMEOUT + 1);
        game.claimTimeout(id); // the sybil never responds
        assertTrue(_game(id).proposerWon, "the false claim was validated by a collusive timeout");
        vm.prank(challenger);
        vm.expectRevert(abi.encodeWithSelector(BisectionGame.WrongPhase.selector, BisectionGame.Phase.Resolved));
        game.challenge{value: CHALLENGER_BOND}(id);
    }

    // ---------------------------------------------------------------------------------------------
    // Fuzz
    // ---------------------------------------------------------------------------------------------

    /// @dev Whatever the length of the computation and wherever the proposer's lie starts, an honest challenger
    ///      wins within 2*ceil(log2 N)+1 moves of bisection and one-step proof.
    function testFuzz_honestChallengerAlwaysBeatsALyingProposer(uint8 stepsSeed, uint8 fromSeed) public {
        uint64 steps = uint64(bound(stepsSeed, 1, MAX_STEPS));
        uint64 from = uint64(bound(fromSeed, 1, steps));
        bytes32[] memory claimed = _liarClaims(steps, from);
        uint256 id = _create(steps, claimed[steps]);
        _challenge(id);
        uint256 moves = _play(id, claimed, true, 0);
        BisectionGame.Game memory g = _game(id);
        assertFalse(g.proposerWon);
        assertEq(g.hi, from); // the game pinpoints the first false state exactly
        assertLe(moves + 2, 2 * _ceilLog2(steps) + 3);
        assertEq(game.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND);
    }

    /// @dev Whatever a (random) challenger answers, an honest proposer never loses a game it opened with the truth.
    function testFuzz_honestProposerAlwaysBeatsARandomChallenger(uint8 stepsSeed, uint256 seed) public {
        uint64 steps = uint64(bound(stepsSeed, 1, MAX_STEPS));
        uint256 id = _create(steps, truth[steps]);
        _challenge(id);
        _play(id, _truthClaims(steps), false, seed);
        assertTrue(_game(id).proposerWon);
        assertEq(game.credits(proposer), PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(game.credits(challenger), 0);
    }

    /// @dev A one-step proof with ANY pre-state that is not the agreed one is refused.
    function testFuzz_oneStepProofRejectsEveryOtherPreState(uint64 pc, uint64[4] memory regs) public {
        uint256 id = _challenged(1);
        VMState memory pre = VMState({pc: pc, regs: regs});
        vm.assume(ToyVM.hashState(pre) != truth[0]);
        vm.expectRevert(BisectionGame.PreStateMismatch.selector);
        game.step(id, program, pre);
    }
}
