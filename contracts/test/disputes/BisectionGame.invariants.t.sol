// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {StdInvariant} from "forge-std/StdInvariant.sol";
import {Test} from "forge-std/Test.sol";
import {BisectionGame} from "../../src/disputes/BisectionGame.sol";
import {VMState} from "../../src/disputes/ToyVM.sol";
import {GameFixture} from "./BisectionGame.t.sol";

/// @notice Random sequences of every public action of the game, several games at once, by three actors who
///         sometimes cheat (wrong midpoints, wrong pre-states, silent clocks). Ghost variables remember verdicts.
contract GameHandler is GameFixture {
    address[3] internal actors;
    mapping(uint256 => bool) public ghostResolved;
    mapping(uint256 => bool) public ghostProposerWon;
    uint256 public ghostVerdictsChanged; // a resolved game whose verdict later differs (must stay 0)
    uint256 public ghostWithdrawn;
    uint256 public stats_resolved;

    function setUp() public override {
        super.setUp();
        actors = [proposer, challenger, bystander];
    }

    function gameContract() external view returns (BisectionGame) {
        return game;
    }

    function actorsList() external view returns (address[3] memory) {
        return actors;
    }

    modifier observed() {
        _;
        _observe();
    }

    // ----- actions -----

    function open(uint256 actorSeed, uint256 stepsSeed, bool lie) external observed {
        address who = actors[actorSeed % 3];
        uint64 steps = uint64(bound(stepsSeed, 1, 64));
        bytes32 claim = lie ? keccak256(abi.encode("lie", stepsSeed)) : truth[steps];
        vm.prank(who);
        try game.createGame{value: PROPOSER_BOND}(truth[0], steps, claim) {} catch {}
    }

    function challengeGame(uint256 actorSeed, uint256 idSeed) external observed {
        uint256 n = game.gameCount();
        if (n == 0) return;
        vm.prank(actors[actorSeed % 3]);
        try game.challenge{value: CHALLENGER_BOND}(idSeed % n) {} catch {}
    }

    function bisectGame(uint256 actorSeed, uint256 idSeed, bool wrongStep, bool lie) external observed {
        uint256 n = game.gameCount();
        if (n == 0) return;
        uint256 id = idSeed % n;
        uint64 mid = game.expectedStep(id);
        if (wrongStep) ++mid;
        vm.prank(actors[actorSeed % 3]);
        try game.bisect(id, mid, lie ? keccak256(abi.encode("lie", mid)) : truth[mid]) {} catch {}
    }

    function respondGame(uint256 actorSeed, uint256 idSeed, bool agree) external observed {
        uint256 n = game.gameCount();
        if (n == 0) return;
        vm.prank(actors[actorSeed % 3]);
        try game.respond(idSeed % n, agree) {} catch {}
    }

    function stepGame(uint256 actorSeed, uint256 idSeed, bool wrongPre) external observed {
        uint256 n = game.gameCount();
        if (n == 0) return;
        uint256 id = idSeed % n;
        VMState memory pre = truthStates[game.getGame(id).lo];
        if (wrongPre) pre.regs[0] += 1;
        vm.prank(actors[actorSeed % 3]);
        try game.step(id, program, pre) {} catch {}
    }

    function timeoutGame(uint256 idSeed) external observed {
        uint256 n = game.gameCount();
        if (n == 0) return;
        try game.claimTimeout(idSeed % n) {} catch {}
    }

    function warp(uint256 seconds_) external observed {
        skip(bound(seconds_, 0, 2 * TIMEOUT));
    }

    function withdrawCredit(uint256 actorSeed) external observed {
        address who = actors[actorSeed % 3];
        uint256 owed = game.credits(who);
        vm.prank(who);
        try game.withdraw() {
            ghostWithdrawn += owed;
        } catch {}
    }

    /// @dev After every action: records verdicts and flags any change of an already resolved game.
    function _observe() internal {
        uint256 n = game.gameCount();
        for (uint256 i = 0; i < n; ++i) {
            BisectionGame.Game memory g = game.getGame(i);
            if (g.phase != BisectionGame.Phase.Resolved) continue;
            if (!ghostResolved[i]) {
                ghostResolved[i] = true;
                ghostProposerWon[i] = g.proposerWon;
                ++stats_resolved;
            } else if (ghostProposerWon[i] != g.proposerWon) {
                ++ghostVerdictsChanged;
            }
        }
    }
}

contract BisectionGameInvariants is StdInvariant, Test {
    GameHandler internal handler;
    BisectionGame internal game;

    function setUp() public {
        handler = new GameHandler();
        handler.setUp();
        game = handler.gameContract();
        targetContract(address(handler));
        bytes4[] memory selectors = new bytes4[](8);
        selectors[0] = handler.open.selector;
        selectors[1] = handler.challengeGame.selector;
        selectors[2] = handler.bisectGame.selector;
        selectors[3] = handler.respondGame.selector;
        selectors[4] = handler.stepGame.selector;
        selectors[5] = handler.timeoutGame.selector;
        selectors[6] = handler.warp.selector;
        selectors[7] = handler.withdrawCredit.selector;
        targetSelector(FuzzSelector({addr: address(handler), selectors: selectors}));
    }

    /// @dev The contract holds exactly: the bonds of games still running plus the credits nobody withdrew yet.
    function invariant_solvency() public view {
        uint256 owed;
        address[3] memory actors = handler.actorsList();
        for (uint256 i = 0; i < 3; ++i) {
            owed += game.credits(actors[i]);
        }
        uint256 locked;
        uint256 n = game.gameCount();
        for (uint256 i = 0; i < n; ++i) {
            BisectionGame.Game memory g = game.getGame(i);
            if (g.phase == BisectionGame.Phase.Resolved) continue;
            locked += game.PROPOSER_BOND();
            if (g.challenger != address(0)) locked += game.CHALLENGER_BOND();
        }
        assertEq(address(game).balance, owed + locked, "balance != credits + bonds of running games");
    }

    /// @dev Every running game has a well-formed interval and a phase consistent with it.
    function invariant_intervalsAreWellFormed() public view {
        uint256 n = game.gameCount();
        for (uint256 i = 0; i < n; ++i) {
            BisectionGame.Game memory g = game.getGame(i);
            assertLt(g.lo, g.hi, "empty interval");
            if (g.phase == BisectionGame.Phase.AwaitingStep) assertEq(g.hi - g.lo, 1, "step phase needs one step");
            if (g.phase == BisectionGame.Phase.AwaitingBisect || g.phase == BisectionGame.Phase.AwaitingResponse) {
                assertGe(g.hi - g.lo, 2, "bisecting a single step");
            }
            if (g.phase == BisectionGame.Phase.AwaitingChallenge) {
                assertEq(g.challenger, address(0));
                assertEq(g.lo, 0);
            }
        }
    }

    /// @dev A verdict never changes once the game is resolved.
    function invariant_verdictsAreFinal() public view {
        assertEq(handler.ghostVerdictsChanged(), 0);
    }
}
