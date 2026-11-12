// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {BridgeFixture} from "./Base.t.sol";
import {L1Bridge} from "../src/l1/L1Bridge.sol";
import {IOutputOracle, OutputStatus} from "../src/interfaces/IOutputOracle.sol";
import {Withdrawal, WithdrawalHash} from "../src/lib/Withdrawals.sol";
import {RevertingTarget, ReturnBombTarget, ToggleTarget} from "./utils/Targets.sol";

contract L1BridgeTest is BridgeFixture {
    event DepositInitiated(uint256 indexed depositId, address indexed from, address indexed to, uint256 amount);
    event WithdrawalProven(bytes32 indexed leaf, uint256 indexed outputIndex, uint256 id);
    event WithdrawalFinalized(bytes32 indexed leaf, address indexed target, uint256 amount, bool paid);
    event Claimed(address indexed account, uint256 amount);
    event PausedSet(bool paused);
    event LimitsUpdated(uint256 maxDepositAmount, uint256 maxTotalLocked);

    // ----- construction / admin -----

    function test_constructorState() public view {
        assertEq(address(l1.ORACLE()), address(oracle));
        assertEq(l1.maxDepositAmount(), MAX_DEPOSIT);
        assertEq(l1.maxTotalLocked(), MAX_LOCKED);
        assertEq(l1.owner(), owner);
        assertFalse(l1.paused());
    }

    function test_RevertWhen_constructorOracleIsZero() public {
        vm.expectRevert(L1Bridge.ZeroAddress.selector);
        new L1Bridge(owner, IOutputOracle(address(0)), 1, 1);
    }

    function test_ownerSetsLimits() public {
        vm.expectEmit(false, false, false, true);
        emit LimitsUpdated(5, 6);
        vm.prank(owner);
        l1.setLimits(5, 6);
        assertEq(l1.maxDepositAmount(), 5);
        assertEq(l1.maxTotalLocked(), 6);
    }

    function test_RevertWhen_setLimitsByNonOwner() public {
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, alice));
        vm.prank(alice);
        l1.setLimits(5, 6);
    }

    // ----- deposits -----

    function test_depositLocksEthAndEmitsEvent() public {
        vm.expectEmit(true, true, true, true);
        emit DepositInitiated(0, alice, bob, 3 ether);
        vm.prank(alice);
        uint256 id = l1.depositETH{value: 3 ether}(bob);
        assertEq(id, 0);
        assertEq(l1.depositCount(), 1);
        assertEq(l1.totalLocked(), 3 ether);
        assertEq(address(l1).balance, 3 ether);

        vm.prank(alice);
        assertEq(l1.depositETH{value: 1 ether}(bob), 1);
    }

    function test_RevertWhen_depositIsInvalid() public {
        vm.startPrank(alice);
        vm.expectRevert(L1Bridge.ZeroAddress.selector);
        l1.depositETH{value: 1 ether}(address(0));
        vm.expectRevert(L1Bridge.ZeroAmount.selector);
        l1.depositETH(bob);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.DepositTooLarge.selector, MAX_DEPOSIT + 1, MAX_DEPOSIT));
        l1.depositETH{value: MAX_DEPOSIT + 1}(bob);
        vm.stopPrank();
    }

    function test_RevertWhen_totalLockedCapExceeded() public {
        vm.prank(owner);
        l1.setLimits(10 ether, 15 ether);
        vm.startPrank(alice);
        l1.depositETH{value: 10 ether}(bob);
        vm.expectRevert(L1Bridge.TotalLockedExceeded.selector);
        l1.depositETH{value: 6 ether}(bob);
        l1.depositETH{value: 5 ether}(bob); // exactly at the cap is fine
        vm.stopPrank();
    }

    function test_bridgeRejectsPlainEthTransfers() public {
        vm.prank(alice);
        (bool ok,) = address(l1).call{value: 1 ether}("");
        assertFalse(ok);
    }

    // ----- proving -----

    function test_proveStoresProof() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 2 ether);
        bytes32 leaf = WithdrawalHash.leaf(w);
        vm.expectEmit(true, true, false, true);
        emit WithdrawalProven(leaf, out, w.id);
        _prove(w, out);
        (uint64 idx, uint64 at, bool exists) = l1.proofs(leaf);
        assertEq(idx, out);
        assertEq(at, block.timestamp);
        assertTrue(exists);
    }

    function test_RevertWhen_proofHasWrongLength() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        bytes32[] memory shortProof = new bytes32[](19);
        vm.expectRevert(L1Bridge.BadProofLength.selector);
        l1.proveWithdrawal(w, out, shortProof);
    }

    function test_RevertWhen_outputUnknown() public {
        (Withdrawal memory w,) = _stage(bob, 1 ether);
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(abi.encodeWithSignature("OutputNotFound(uint256)", 9));
        l1.proveWithdrawal(w, 9, p);
    }

    function test_RevertWhen_outputChallengedOrDeleted() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(out);
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.OutputNotProvable.selector, OutputStatus.Challenged));
        l1.proveWithdrawal(w, out, p);
        vm.prank(guardian);
        oracle.deleteFrom(out);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.OutputNotProvable.selector, OutputStatus.Deleted));
        l1.proveWithdrawal(w, out, p);
    }

    function test_RevertWhen_proofIsInvalid() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        Withdrawal memory tampered = Withdrawal({id: w.id, sender: w.sender, target: w.target, amount: w.amount + 1});
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(L1Bridge.InvalidProof.selector);
        l1.proveWithdrawal(tampered, out, p);
    }

    function test_RevertWhen_idNotCoveredByOutput() public {
        _deposit(alice, alice, 4 ether);
        Withdrawal memory w0 = _withdraw(alice, bob, 1 ether);
        uint256 out = _propose(); // covers only id 0
        Withdrawal memory w1 = _withdraw(alice, bob, 1 ether);
        bytes32[] memory p = _proof(w1.id, 2);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.IdOutOfRange.selector, 1, 1));
        l1.proveWithdrawal(w1, out, p);
        _prove(w0, out); // id 0 is fine
    }

    function test_RevertWhen_provenTwiceAgainstLiveOutput() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        _prove(w, out);
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(L1Bridge.AlreadyProven.selector);
        l1.proveWithdrawal(w, out, p);
    }

    // ----- finalizing -----

    function test_finalizePaysTargetAfterWindow() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 2 ether);
        _prove(w, out);
        _pastWindow();
        uint256 before = bob.balance;
        vm.expectEmit(true, true, false, true);
        emit WithdrawalFinalized(WithdrawalHash.leaf(w), bob, 2 ether, true);
        l1.finalizeWithdrawal(w);
        assertEq(bob.balance, before + 2 ether);
        assertEq(l1.totalLocked(), 0);
        assertTrue(l1.finalized(WithdrawalHash.leaf(w)));
        assertEq(address(l1).balance, 0);
    }

    function test_RevertWhen_finalizeWithoutProof() public {
        (Withdrawal memory w,) = _stage(bob, 1 ether);
        vm.expectRevert(L1Bridge.NotProven.selector);
        l1.finalizeWithdrawal(w);
    }

    function test_RevertWhen_finalizeBeforeWindow() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        _prove(w, out);
        skip(WINDOW - 1);
        vm.expectRevert(L1Bridge.OutputNotFinalizable.selector);
        l1.finalizeWithdrawal(w);
    }

    function test_RevertWhen_lockedFundsAreInsufficient() public {
        // L2 holds more than L1 locked (an unbacked mint). Emulate: relay a deposit that never happened on L1.
        vm.prank(relayer);
        l2.finalizeDeposit(99, alice, 5 ether);
        Withdrawal memory w = _withdraw(alice, bob, 5 ether);
        uint256 out = _propose();
        _prove(w, out);
        _pastWindow();
        vm.expectRevert(L1Bridge.InsufficientLocked.selector);
        l1.finalizeWithdrawal(w);
    }

    function test_returnBombTargetCannotBurnTheCallersGas() public {
        address bomb = address(new ReturnBombTarget());
        (Withdrawal memory w, uint256 out) = _stage(bomb, 1 ether);
        _prove(w, out);
        _pastWindow();
        uint256 g = gasleft();
        l1.finalizeWithdrawal(w);
        assertLt(g - gasleft(), 200_000);
        assertEq(bomb.balance, 1 ether);
    }

    // ----- pull payments -----

    function test_failedPayoutIsCreditedThenClaimed() public {
        ToggleTarget t = new ToggleTarget();
        (Withdrawal memory w, uint256 out) = _stage(address(t), 2 ether);
        _prove(w, out);
        _pastWindow();
        vm.expectEmit(true, true, false, true);
        emit WithdrawalFinalized(WithdrawalHash.leaf(w), address(t), 2 ether, false);
        l1.finalizeWithdrawal(w);
        assertEq(l1.claimable(address(t)), 2 ether);
        assertEq(l1.totalClaimable(), 2 ether);
        assertEq(l1.totalLocked(), 0);

        vm.expectRevert(L1Bridge.TransferFailed.selector);
        t.callClaim(l1); // still closed: reverts, credit preserved
        assertEq(l1.claimable(address(t)), 2 ether);

        t.setOpen(true);
        vm.expectEmit(true, false, false, true);
        emit Claimed(address(t), 2 ether);
        t.callClaim(l1);
        assertEq(address(t).balance, 2 ether);
        assertEq(l1.totalClaimable(), 0);
    }

    function test_RevertWhen_nothingToClaim() public {
        vm.expectRevert(L1Bridge.NothingToClaim.selector);
        l1.claim();
    }

    // ----- pause -----

    function test_guardianPausesAndUnpauses() public {
        vm.expectEmit(false, false, false, true);
        emit PausedSet(true);
        vm.prank(guardian);
        l1.pause();
        assertTrue(l1.paused());

        vm.expectRevert(L1Bridge.IsPaused.selector);
        vm.prank(alice);
        l1.depositETH{value: 1}(bob);

        vm.prank(guardian);
        l1.unpause();
        assertFalse(l1.paused());
    }

    function test_RevertWhen_pauseOrUnpauseByNonGuardian() public {
        vm.expectRevert(L1Bridge.NotGuardian.selector);
        vm.prank(owner);
        l1.pause();
        vm.expectRevert(L1Bridge.NotGuardian.selector);
        vm.prank(owner);
        l1.unpause();
    }

    function test_pausedBridgeBlocksProveAndFinalize() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        _prove(w, out);
        _pastWindow();
        vm.prank(guardian);
        l1.pause();
        vm.expectRevert(L1Bridge.IsPaused.selector);
        l1.finalizeWithdrawal(w);
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(L1Bridge.IsPaused.selector);
        l1.proveWithdrawal(w, out, p);
    }

    function test_RevertWhen_oracleDeleteRevertsTheProofsOfThatOutput() public {
        // proving against a deleted output is impossible; kept here as a status-table sanity check
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        vm.prank(guardian);
        oracle.deleteFrom(out);
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.OutputNotProvable.selector, OutputStatus.Deleted));
        l1.proveWithdrawal(w, out, p);
    }

    function test_revertingTargetHelperReallyReverts() public {
        RevertingTarget r = new RevertingTarget();
        (bool ok,) = address(r).call{value: 1}("");
        assertFalse(ok);
    }
}
