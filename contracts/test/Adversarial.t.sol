// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {BridgeFixture} from "./Base.t.sol";
import {L1Bridge} from "../src/l1/L1Bridge.sol";
import {L2Bridge} from "../src/l2/L2Bridge.sol";
import {OutputOracle} from "../src/l1/OutputOracle.sol";
import {OutputStatus} from "../src/interfaces/IOutputOracle.sol";
import {Withdrawal, WithdrawalHash} from "../src/lib/Withdrawals.sol";
import {TreeBuilder} from "./utils/TreeBuilder.sol";
import {RevertingTarget, ReentrantTarget} from "./utils/Targets.sol";

/// @notice One named test per row of the adversarial table in PLAN.md (Phase 1), plus the sibling attacks that
///         come with them. Row numbers refer to the table order.
contract AdversarialTest is BridgeFixture {
    // Row 1: finalize twice (same or different proving output)

    function test_attack_finalizeTwiceSameOutput() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        _prove(w, out);
        _pastWindow();
        l1.finalizeWithdrawal(w);
        vm.expectRevert(L1Bridge.AlreadyFinalized.selector);
        l1.finalizeWithdrawal(w);
    }

    function test_attack_finalizeTwiceViaADifferentOutput() public {
        (Withdrawal memory w, uint256 out0) = _stage(bob, 1 ether);
        _prove(w, out0);
        uint256 out1 = _propose(); // a later output covering the same withdrawal
        _pastWindow();
        l1.finalizeWithdrawal(w);
        // re-prove against the second output after paying: blocked
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(L1Bridge.AlreadyFinalized.selector);
        l1.proveWithdrawal(w, out1, p);
        assertEq(address(l1).balance, 0);
    }

    // Row 2: forged proof, proof from another output, flipped sibling

    function test_attack_forgedWithdrawalWithRealProof() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        Withdrawal memory forged = Withdrawal({id: w.id, sender: w.sender, target: alice, amount: 1 ether});
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(L1Bridge.InvalidProof.selector);
        l1.proveWithdrawal(forged, out, p);
    }

    function test_attack_proofFromAnotherOutputDoesNotVerify() public {
        _deposit(alice, alice, 4 ether);
        Withdrawal memory w0 = _withdraw(alice, bob, 1 ether);
        uint256 out0 = _propose(); // root of 1 leaf
        _withdraw(alice, bob, 1 ether);
        _propose(); // root of 2 leaves
        // proof computed against the 2-leaf tree used with the 1-leaf output
        bytes32[] memory p2 = _proof(w0.id, 2);
        vm.expectRevert(L1Bridge.InvalidProof.selector);
        l1.proveWithdrawal(w0, out0, p2);
    }

    function test_attack_flippedSiblingFails() public {
        _deposit(alice, alice, 4 ether);
        Withdrawal memory w = _withdraw(alice, bob, 1 ether);
        _withdraw(alice, bob, 1 ether);
        uint256 out = _propose();
        bytes32[] memory p = _proof(w.id, 2);
        p[0] = bytes32(uint256(p[0]) ^ 1);
        vm.expectRevert(L1Bridge.InvalidProof.selector);
        l1.proveWithdrawal(w, out, p);
    }

    function test_attack_innerNodeAsLeafFails() public {
        // Attacker tries to "withdraw" using an inner node: a Withdrawal whose fields are chosen so that the
        // leaf equals an inner node is impossible (128-byte vs 64-byte preimage); the best they can do is a
        // short proof, which BadProofLength rejects.
        _deposit(alice, alice, 4 ether);
        _withdraw(alice, bob, 1 ether);
        _withdraw(alice, bob, 1 ether);
        uint256 out = _propose();
        Withdrawal memory fake = Withdrawal({id: 0, sender: alice, target: alice, amount: 100 ether});
        bytes32[] memory shortProof = new bytes32[](19);
        vm.expectRevert(L1Bridge.BadProofLength.selector);
        l1.proveWithdrawal(fake, out, shortProof);
    }

    // Row 3: prove with a tree index at or above withdrawalCount

    function test_attack_proveIdAtOrAboveWithdrawalCount() public {
        _deposit(alice, alice, 4 ether);
        _withdraw(alice, bob, 1 ether);
        uint256 out = _propose(); // count = 1
        Withdrawal memory w1 = _withdraw(alice, bob, 1 ether); // id 1, not in the output
        bytes32[] memory p = _proof(w1.id, 2);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.IdOutOfRange.selector, 1, 1));
        l1.proveWithdrawal(w1, out, p);
    }

    // Row 4: finalize before the window; finalize while challenged

    function test_attack_finalizeBeforeWindow() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        _prove(w, out);
        vm.expectRevert(L1Bridge.OutputNotFinalizable.selector);
        l1.finalizeWithdrawal(w);
    }

    function test_attack_finalizeWhileChallenged() public {
        (Withdrawal memory w, uint256 out) = _stage(bob, 1 ether);
        _prove(w, out);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(out);
        _pastWindow();
        vm.expectRevert(L1Bridge.OutputNotFinalizable.selector);
        l1.finalizeWithdrawal(w);
    }

    function test_attack_laterOutputCannotFinalizeWhileAnEarlierOneIsChallenged() public {
        _deposit(alice, alice, 4 ether);
        Withdrawal memory w0 = _withdraw(alice, bob, 1 ether);
        uint256 out0 = _propose();
        skip(WINDOW / 2);
        Withdrawal memory w1 = _withdraw(alice, bob, 1 ether);
        uint256 out1 = _propose();
        _prove(w1, out1);
        _prove(w0, out0);
        // out0 is challenged right before its window ends
        skip(WINDOW / 2 - 1);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(out0);
        // out1's own window ends later, but even then nothing finalizes while out0 is open
        skip(WINDOW);
        vm.expectRevert(L1Bridge.OutputNotFinalizable.selector);
        l1.finalizeWithdrawal(w1);
    }

    // Row 5: challenge after the window or without bond

    function test_attack_challengeAfterWindow() public {
        (, uint256 out) = _stage(bob, 1 ether);
        _pastWindow();
        vm.expectRevert(OutputOracle.WindowClosed.selector);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(out);
    }

    function test_attack_challengeWithoutBond() public {
        (, uint256 out) = _stage(bob, 1 ether);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongBond.selector, CHALLENGER_BOND, 0));
        vm.prank(challenger);
        oracle.challengeOutput(out);
    }

    // Row 6: non-proposer proposes; non-guardian resolves; non-relayer finalizes a deposit

    function test_attack_nonProposerProposes() public {
        vm.expectRevert(OutputOracle.NotProposer.selector);
        vm.prank(bob);
        oracle.proposeOutput{value: PROPOSER_BOND}(bytes32(uint256(1)), bytes32(uint256(2)), 1, 0);
    }

    function test_attack_nonGuardianResolves() public {
        (, uint256 out) = _stage(bob, 1 ether);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(out);
        vm.expectRevert(OutputOracle.NotGuardian.selector);
        vm.prank(challenger); // even the challenger cannot judge their own challenge
        oracle.resolveChallenge(out, true);
        vm.expectRevert(OutputOracle.NotGuardian.selector);
        vm.prank(proposer);
        oracle.resolveChallenge(out, false);
    }

    function test_attack_nonRelayerFinalizesDeposit() public {
        vm.expectRevert(L2Bridge.NotRelayer.selector);
        vm.prank(bob);
        l2.finalizeDeposit(0, bob, 1000 ether);
        assertEq(token.totalSupply(), 0);
    }

    // Row 7: replay of a deposit on L2

    function test_attack_depositReplayOnL2() public {
        _deposit(alice, alice, 1 ether);
        vm.expectRevert(abi.encodeWithSelector(L2Bridge.DepositAlreadyProcessed.selector, 0));
        vm.prank(relayer);
        l2.finalizeDeposit(0, alice, 1 ether);
        assertEq(token.totalSupply(), 1 ether);
    }

    // Row 8: target reverts on receive

    function test_attack_revertingTargetIsCreditedAndDoesNotBlockTheBridge() public {
        address bad = address(new RevertingTarget());
        _deposit(alice, alice, 6 ether);
        Withdrawal memory wBad = _withdraw(alice, bad, 2 ether);
        Withdrawal memory wGood = _withdraw(alice, bob, 3 ether);
        uint256 out = _propose();
        _prove(wBad, out);
        _prove(wGood, out);
        _pastWindow();

        uint256 before = bob.balance;
        l1.finalizeWithdrawal(wBad); // payout fails -> credited
        assertEq(l1.claimable(bad), 2 ether);
        l1.finalizeWithdrawal(wGood); // the next one is not blocked
        assertEq(bob.balance, before + 3 ether);
        assertEq(address(l1).balance, 2 ether + 1 ether); // credit + remaining locked
        assertEq(address(l1).balance, l1.totalLocked() + l1.totalClaimable());
    }

    // Row 9: target re-enters finalizeWithdrawal or claim

    function test_attack_targetReentersFinalize() public {
        ReentrantTarget evil = new ReentrantTarget(l1);
        _deposit(alice, alice, 4 ether);
        Withdrawal memory w = _withdraw(alice, address(evil), 2 ether);
        Withdrawal memory other = _withdraw(alice, bob, 2 ether);
        uint256 out = _propose();
        _prove(w, out);
        _prove(other, out);
        _pastWindow();
        evil.arm(other, true, false); // while receiving, try to finalize ANOTHER withdrawal re-entrantly
        l1.finalizeWithdrawal(w);
        assertEq(evil.blockedReentries(), 1);
        assertEq(address(evil).balance, 2 ether);
        assertFalse(l1.finalized(WithdrawalHash.leaf(other))); // the re-entrant call did nothing
        assertEq(address(l1).balance, l1.totalLocked() + l1.totalClaimable());
    }

    function test_attack_targetReentersClaim() public {
        ReentrantTarget evil = new ReentrantTarget(l1);
        _deposit(alice, alice, 4 ether);
        Withdrawal memory w = _withdraw(alice, address(evil), 4 ether);
        uint256 out = _propose();
        _prove(w, out);
        _pastWindow();

        evil.setRejecting(true); // payout fails -> credited
        l1.finalizeWithdrawal(w);
        assertEq(l1.claimable(address(evil)), 4 ether);

        evil.setRejecting(false);
        evil.arm(w, false, true); // while receiving the claim, try to claim again
        evil.callClaim();
        assertEq(evil.blockedReentries(), 1);
        assertEq(address(evil).balance, 4 ether); // paid exactly once
        assertEq(l1.totalClaimable(), 0);
        assertEq(address(l1).balance, 0);
    }

    // Row 10: fake leaf in a root, deleted via deleteFrom, a later output also deleted

    function test_attack_fakeLeafOutputDeletedAndNeverFinalizes() public {
        _deposit(alice, alice, 5 ether);
        Withdrawal memory real = _withdraw(alice, alice, 1 ether);

        // The malicious proposer posts a root with an extra, fabricated leaf (never emitted by L2).
        Withdrawal memory fake = Withdrawal({id: 1, sender: bob, target: bob, amount: 5 ether});
        bytes32[] memory forgedLeaves = new bytes32[](2);
        forgedLeaves[0] = leaves[0];
        forgedLeaves[1] = WithdrawalHash.leaf(fake);
        vm.prank(proposer);
        uint256 bad =
            oracle.proposeOutput{value: PROPOSER_BOND}(TreeBuilder.root(forgedLeaves), bytes32(uint256(7)), 5, 2);
        // ... and, being the proposer, a later output built on top of it
        vm.prank(proposer);
        uint256 later =
            oracle.proposeOutput{value: PROPOSER_BOND}(TreeBuilder.root(forgedLeaves), bytes32(uint256(8)), 6, 2);

        // The fake withdrawal is provable against the bad output while it is merely "Proposed" ...
        l1.proveWithdrawal(fake, bad, TreeBuilder.proof(forgedLeaves, 1));

        // ... the challenger notices, the guardian rules against the proposer.
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(bad);
        vm.prank(guardian);
        oracle.resolveChallenge(bad, true);

        assertEq(uint8(oracle.getOutput(bad).status), uint8(OutputStatus.Deleted));
        assertEq(uint8(oracle.getOutput(later).status), uint8(OutputStatus.Deleted));

        // The fake withdrawal can never finalize, not even after any amount of time.
        skip(365 days);
        vm.expectRevert(L1Bridge.OutputNotFinalizable.selector);
        l1.finalizeWithdrawal(fake);
        // and proving it again against the deleted later output fails
        bytes32[] memory p = TreeBuilder.proof(forgedLeaves, 1);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.OutputNotProvable.selector, OutputStatus.Deleted));
        l1.proveWithdrawal(fake, later, p);
        assertEq(bob.balance, 1000 ether); // untouched
        assertEq(real.amount, 1 ether);
    }

    // Row 11: deleted output, then re-prove against a good output: works once

    function test_attack_reproveAfterDeletionWorksExactlyOnce() public {
        (Withdrawal memory w, uint256 out0) = _stage(bob, 2 ether);
        _prove(w, out0);
        vm.prank(guardian);
        oracle.deleteFrom(out0); // e.g. emergency deletion

        uint256 out1 = _propose(); // honest re-proposal covering the same withdrawal
        _prove(w, out1); // allowed because the earlier output was deleted
        bytes32[] memory p = _proof(w.id, 1);
        vm.expectRevert(L1Bridge.AlreadyProven.selector);
        l1.proveWithdrawal(w, out1, p); // second time is rejected
        _pastWindow();
        uint256 before = bob.balance;
        l1.finalizeWithdrawal(w);
        assertEq(bob.balance, before + 2 ether);
        vm.expectRevert(L1Bridge.AlreadyFinalized.selector);
        l1.finalizeWithdrawal(w);
    }

    function test_attack_finalizeUsingTheProofOfADeletedOutputFails() public {
        (Withdrawal memory w, uint256 out0) = _stage(bob, 2 ether);
        _prove(w, out0);
        vm.prank(guardian);
        oracle.deleteFrom(out0);
        _propose();
        _pastWindow();
        vm.expectRevert(L1Bridge.OutputNotFinalizable.selector);
        l1.finalizeWithdrawal(w); // still points at the deleted output
    }

    // Row 12: pause with in-flight withdrawals; claim still works

    function test_attack_pauseDuringInFlightWithdrawalsKeepsClaimWorking() public {
        address bad = address(new RevertingTarget());
        _deposit(alice, alice, 3 ether);
        Withdrawal memory wBad = _withdraw(alice, bad, 1 ether);
        Withdrawal memory wBob = _withdraw(alice, bob, 2 ether);
        uint256 out = _propose();
        _prove(wBad, out);
        _prove(wBob, out);
        _pastWindow();
        l1.finalizeWithdrawal(wBad); // credited (target rejects)
        assertEq(l1.claimable(bad), 1 ether);

        vm.prank(guardian);
        l1.pause();
        vm.expectRevert(L1Bridge.IsPaused.selector);
        l1.finalizeWithdrawal(wBob); // in flight, blocked

        // claim() is not blocked by the pause (here it fails only because `bad` still rejects ETH)
        vm.prank(bad);
        vm.expectRevert(L1Bridge.TransferFailed.selector);
        l1.claim();

        vm.prank(guardian);
        l1.unpause();
        uint256 before = bob.balance;
        l1.finalizeWithdrawal(wBob);
        assertEq(bob.balance, before + 2 ether);
    }

    // Row 13: deposit cap and maxTotalLocked exceeded

    function test_attack_depositCapAndTotalLockedExceeded() public {
        vm.startPrank(alice);
        vm.expectRevert(abi.encodeWithSelector(L1Bridge.DepositTooLarge.selector, MAX_DEPOSIT + 1, MAX_DEPOSIT));
        l1.depositETH{value: MAX_DEPOSIT + 1}(alice);
        vm.stopPrank();

        vm.prank(owner);
        l1.setLimits(MAX_DEPOSIT, MAX_DEPOSIT + 1 ether);
        vm.startPrank(alice);
        l1.depositETH{value: MAX_DEPOSIT}(alice);
        vm.expectRevert(L1Bridge.TotalLockedExceeded.selector);
        l1.depositETH{value: 2 ether}(alice);
        vm.stopPrank();
    }

    // Extra: unbacked mint on L2 (bad relayer) cannot drain what L1 does not hold

    function test_attack_unbackedL2MintCannotPayMoreThanLocked() public {
        _deposit(alice, alice, 2 ether); // 2 ETH really locked
        vm.prank(relayer);
        l2.finalizeDeposit(500, bob, 50 ether); // relayer mints 50 ETH out of thin air on L2
        Withdrawal memory w = _withdraw(bob, bob, 50 ether);
        uint256 out = _propose();
        _prove(w, out);
        _pastWindow();
        vm.expectRevert(L1Bridge.InsufficientLocked.selector);
        l1.finalizeWithdrawal(w);
        assertEq(address(l1).balance, 2 ether); // honest depositor's funds are intact
    }
}
