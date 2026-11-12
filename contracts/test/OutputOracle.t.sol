// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {BridgeFixture} from "./Base.t.sol";
import {OutputOracle} from "../src/l1/OutputOracle.sol";
import {Output, OutputStatus} from "../src/interfaces/IOutputOracle.sol";

contract OutputOracleTest is BridgeFixture {
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
    event OutputDeleted(uint256 indexed index);

    // ----- construction and admin -----

    function test_constructorStoresParameters() public view {
        assertEq(oracle.CHALLENGE_WINDOW(), WINDOW);
        assertEq(oracle.RESOLUTION_PERIOD(), RESOLUTION);
        assertEq(oracle.PROPOSER_BOND(), PROPOSER_BOND);
        assertEq(oracle.CHALLENGER_BOND(), CHALLENGER_BOND);
        assertEq(oracle.proposer(), proposer);
        assertEq(oracle.guardian(), guardian);
        assertEq(oracle.owner(), owner);
        assertEq(oracle.outputCount(), 0);
    }

    function test_RevertWhen_constructorGetsZeroAddresses() public {
        vm.expectRevert(OutputOracle.ZeroAddress.selector);
        new OutputOracle(owner, address(0), guardian, 1, 1, 1, 1);
        vm.expectRevert(OutputOracle.ZeroAddress.selector);
        new OutputOracle(owner, proposer, address(0), 1, 1, 1, 1);
    }

    function test_RevertWhen_constructorGetsZeroParameters() public {
        vm.expectRevert(OutputOracle.ZeroParameter.selector);
        new OutputOracle(owner, proposer, guardian, 0, 1, 1, 1);
        vm.expectRevert(OutputOracle.ZeroParameter.selector);
        new OutputOracle(owner, proposer, guardian, 1, 0, 1, 1);
        vm.expectRevert(OutputOracle.ZeroParameter.selector);
        new OutputOracle(owner, proposer, guardian, 1, 1, 0, 1);
        vm.expectRevert(OutputOracle.ZeroParameter.selector);
        new OutputOracle(owner, proposer, guardian, 1, 1, 1, 0);
    }

    function test_ownerRotatesRoles() public {
        vm.startPrank(owner);
        oracle.setProposer(bob);
        oracle.setGuardian(alice);
        vm.stopPrank();
        assertEq(oracle.proposer(), bob);
        assertEq(oracle.guardian(), alice);
    }

    function test_RevertWhen_rolesRotatedByNonOwnerOrToZero() public {
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, alice));
        vm.prank(alice);
        oracle.setProposer(bob);
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, alice));
        vm.prank(alice);
        oracle.setGuardian(bob);
        vm.startPrank(owner);
        vm.expectRevert(OutputOracle.ZeroAddress.selector);
        oracle.setProposer(address(0));
        vm.expectRevert(OutputOracle.ZeroAddress.selector);
        oracle.setGuardian(address(0));
        vm.stopPrank();
    }

    // ----- proposing -----

    function test_proposeStoresOutputAndHoldsBond() public {
        vm.expectEmit(true, true, false, true);
        emit OutputProposed(0, proposer, bytes32(uint256(1)), bytes32(uint256(2)), 10, 3);
        vm.prank(proposer);
        uint256 i = oracle.proposeOutput{value: PROPOSER_BOND}(bytes32(uint256(1)), bytes32(uint256(2)), 10, 3);
        assertEq(i, 0);
        Output memory o = oracle.getOutput(0);
        assertEq(o.withdrawalRoot, bytes32(uint256(1)));
        assertEq(o.l2BlockHash, bytes32(uint256(2)));
        assertEq(o.l2BlockNumber, 10);
        assertEq(o.withdrawalCount, 3);
        assertEq(o.proposedAt, block.timestamp);
        assertEq(uint8(o.status), uint8(OutputStatus.Proposed));
        assertEq(o.proposer, proposer);
        assertEq(address(oracle).balance, PROPOSER_BOND);
        assertEq(oracle.outputCount(), 1);
    }

    function test_RevertWhen_nonProposerProposes() public {
        vm.expectRevert(OutputOracle.NotProposer.selector);
        vm.prank(alice);
        oracle.proposeOutput{value: PROPOSER_BOND}(bytes32(0), bytes32(0), 1, 0);
    }

    function test_RevertWhen_proposeWithWrongBond() public {
        vm.startPrank(proposer);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongBond.selector, PROPOSER_BOND, 0));
        oracle.proposeOutput(bytes32(0), bytes32(0), 1, 0);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongBond.selector, PROPOSER_BOND, PROPOSER_BOND + 1));
        oracle.proposeOutput{value: PROPOSER_BOND + 1}(bytes32(0), bytes32(0), 1, 0);
        vm.stopPrank();
    }

    function test_RevertWhen_proposalIsNotMonotonic() public {
        _propose(); // block 1, count 0
        vm.startPrank(proposer);
        vm.expectRevert(OutputOracle.NotMonotonic.selector);
        oracle.proposeOutput{value: PROPOSER_BOND}(bytes32(0), bytes32(0), 1, 0); // same block
        vm.stopPrank();

        _deposit(alice, alice, 1 ether);
        _withdraw(alice, bob, 1 ether);
        _propose(); // block 2, count 1
        vm.startPrank(proposer);
        vm.expectRevert(OutputOracle.NotMonotonic.selector);
        oracle.proposeOutput{value: PROPOSER_BOND}(bytes32(0), bytes32(0), 3, 0); // count shrinks
        vm.stopPrank();
    }

    function test_latestLiveSkipsDeletedOutputs() public {
        (bool found,) = oracle.latestLive();
        assertFalse(found);
        _propose();
        _propose();
        (bool f, uint256 idx) = oracle.latestLive();
        assertTrue(f);
        assertEq(idx, 1);
        vm.prank(guardian);
        oracle.deleteFrom(1);
        (, idx) = oracle.latestLive();
        assertEq(idx, 0);
        vm.prank(guardian);
        oracle.deleteFrom(0);
        (f,) = oracle.latestLive();
        assertFalse(f);
    }

    function test_proposalAfterDeletionMayReuseBlockNumberOfDeletedOutput() public {
        _propose(); // out 0, block 1
        _propose(); // out 1, block 2
        vm.prank(guardian);
        oracle.deleteFrom(1);
        vm.prank(proposer);
        oracle.proposeOutput{value: PROPOSER_BOND}(bytes32(0), bytes32(0), 2, 0); // block 2 again: allowed
        assertEq(oracle.outputCount(), 3);
    }

    // ----- finality and proposer bond -----

    function test_isFinalizableOnlyAfterWindow() public {
        uint256 i = _propose();
        assertFalse(oracle.isFinalizable(i));
        skip(WINDOW - 1);
        assertFalse(oracle.isFinalizable(i));
        skip(1);
        assertTrue(oracle.isFinalizable(i));
        assertFalse(oracle.isFinalizable(i + 1)); // out of range
    }

    function test_proposerClaimsBondAfterWindow() public {
        uint256 i = _propose();
        _pastWindow();
        oracle.claimProposerBond(i);
        assertEq(oracle.credits(proposer), PROPOSER_BOND);
        uint256 before = proposer.balance;
        vm.prank(proposer);
        oracle.withdrawCredits();
        assertEq(proposer.balance, before + PROPOSER_BOND);
        assertEq(oracle.credits(proposer), 0);
        assertEq(address(oracle).balance, 0);
    }

    function test_RevertWhen_bondClaimedTwiceOrEarly() public {
        uint256 i = _propose();
        vm.expectRevert(OutputOracle.OutputNotFinalizable.selector);
        oracle.claimProposerBond(i);
        _pastWindow();
        oracle.claimProposerBond(i);
        vm.expectRevert(OutputOracle.NothingToWithdraw.selector);
        oracle.claimProposerBond(i);
    }

    function test_RevertWhen_withdrawingNothing() public {
        vm.expectRevert(OutputOracle.NothingToWithdraw.selector);
        oracle.withdrawCredits();
    }

    function test_RevertWhen_creditsGoToAnAccountThatRejectsEth() public {
        ContractProposer cp = new ContractProposer(oracle);
        vm.deal(address(cp), 10 ether);
        vm.prank(owner);
        oracle.setProposer(address(cp));
        uint256 i = cp.propose(PROPOSER_BOND);
        _pastWindow();
        oracle.claimProposerBond(i);
        vm.expectRevert(OutputOracle.TransferFailed.selector);
        cp.pull();
        assertEq(oracle.credits(address(cp)), PROPOSER_BOND); // nothing lost
    }

    // ----- challenging -----

    function test_challengeMarksOutputAndBlocksFinality() public {
        uint256 i = _propose();
        vm.expectEmit(true, true, false, false);
        emit OutputChallenged(i, challenger);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(i);
        Output memory o = oracle.getOutput(i);
        assertEq(uint8(o.status), uint8(OutputStatus.Challenged));
        assertEq(o.challenger, challenger);
        assertEq(o.challengedAt, block.timestamp);
        assertEq(oracle.openChallenges(), 1);
        _pastWindow();
        assertFalse(oracle.isFinalizable(i));
    }

    function test_RevertWhen_challengeIsInvalid() public {
        uint256 i = _propose();
        vm.startPrank(challenger);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongBond.selector, CHALLENGER_BOND, 0));
        oracle.challengeOutput(i);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.OutputNotFound.selector, 5));
        oracle.challengeOutput{value: CHALLENGER_BOND}(5);
        oracle.challengeOutput{value: CHALLENGER_BOND}(i);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongStatus.selector, OutputStatus.Challenged));
        oracle.challengeOutput{value: CHALLENGER_BOND}(i);
        vm.stopPrank();
    }

    function test_RevertWhen_challengeAfterWindow() public {
        uint256 i = _propose();
        _pastWindow();
        vm.expectRevert(OutputOracle.WindowClosed.selector);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(i);
    }

    function test_challengeAtLastSecondOfWindowStillWorks() public {
        uint256 i = _propose();
        skip(WINDOW - 1);
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(i);
        assertEq(oracle.openChallenges(), 1);
    }

    // ----- resolution -----

    function _challenged() internal returns (uint256 i) {
        i = _propose();
        vm.prank(challenger);
        oracle.challengeOutput{value: CHALLENGER_BOND}(i);
    }

    function test_resolveProposerWrongPaysChallengerAndDeletesLaterOutputs() public {
        uint256 i = _challenged();
        _propose(); // later output 1
        _propose(); // later output 2
        vm.expectEmit(true, false, false, true);
        emit ChallengeResolved(i, true);
        vm.prank(guardian);
        oracle.resolveChallenge(i, true);

        for (uint256 k = 0; k < 3; ++k) {
            assertEq(uint8(oracle.getOutput(k).status), uint8(OutputStatus.Deleted));
        }
        assertEq(oracle.openChallenges(), 0);
        // challenger: own bond back + slashed proposer bond; the two collateral outputs refund the proposer
        assertEq(oracle.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(oracle.credits(proposer), 2 * PROPOSER_BOND);
        assertEq(address(oracle).balance, 3 * PROPOSER_BOND + CHALLENGER_BOND);
    }

    function test_resolveChallengerWrongPaysProposerAndRestartsWindow() public {
        uint256 i = _challenged();
        skip(RESOLUTION);
        vm.prank(guardian);
        oracle.resolveChallenge(i, false);
        Output memory o = oracle.getOutput(i);
        assertEq(uint8(o.status), uint8(OutputStatus.Proposed));
        assertEq(o.challenger, address(0));
        assertEq(o.proposedAt, block.timestamp);
        assertEq(oracle.openChallenges(), 0);
        assertEq(oracle.credits(proposer), CHALLENGER_BOND);
        assertFalse(oracle.isFinalizable(i));
        _pastWindow();
        assertTrue(oracle.isFinalizable(i));
    }

    function test_RevertWhen_resolveByNonGuardianOrWrongState() public {
        uint256 i = _propose();
        vm.expectRevert(OutputOracle.NotGuardian.selector);
        oracle.resolveChallenge(i, true);
        vm.startPrank(guardian);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongStatus.selector, OutputStatus.Proposed));
        oracle.resolveChallenge(i, true);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.OutputNotFound.selector, 9));
        oracle.resolveChallenge(9, true);
        vm.stopPrank();
    }

    function test_RevertWhen_resolveAfterResolutionPeriod() public {
        uint256 i = _challenged();
        skip(RESOLUTION + 1);
        vm.expectRevert(OutputOracle.ResolutionExpired.selector);
        vm.prank(guardian);
        oracle.resolveChallenge(i, true);
    }

    function test_expireChallengeFailsSafeAndRefundsEveryone() public {
        uint256 i = _challenged();
        _propose();
        vm.expectRevert(OutputOracle.ResolutionStillOpen.selector);
        oracle.expireChallenge(i);
        skip(RESOLUTION + 1);
        oracle.expireChallenge(i);
        assertEq(uint8(oracle.getOutput(i).status), uint8(OutputStatus.Deleted));
        assertEq(uint8(oracle.getOutput(i + 1).status), uint8(OutputStatus.Deleted));
        assertEq(oracle.openChallenges(), 0);
        assertEq(oracle.credits(challenger), CHALLENGER_BOND);
        assertEq(oracle.credits(proposer), 2 * PROPOSER_BOND);
        assertEq(address(oracle).balance, 2 * PROPOSER_BOND + CHALLENGER_BOND);
    }

    function test_RevertWhen_expiringANonChallengedOutput() public {
        uint256 i = _propose();
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongStatus.selector, OutputStatus.Proposed));
        oracle.expireChallenge(i);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.OutputNotFound.selector, 4));
        oracle.expireChallenge(4);
    }

    function test_cascadeRefundsChallengerOfALaterChallengedOutput() public {
        uint256 first = _challenged();
        // second output also challenged by someone else
        uint256 second = _propose();
        address other = makeAddr("other");
        vm.deal(other, 10 ether);
        vm.prank(other);
        oracle.challengeOutput{value: CHALLENGER_BOND}(second);
        assertEq(oracle.openChallenges(), 2);

        vm.prank(guardian);
        oracle.resolveChallenge(first, true);

        assertEq(oracle.openChallenges(), 0);
        assertEq(oracle.credits(other), CHALLENGER_BOND); // refunded, not rewarded
        assertEq(oracle.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(oracle.credits(proposer), PROPOSER_BOND); // second output's bond
    }

    function test_cascadeSkipsAlreadyDeletedAndSettledOutputs() public {
        uint256 a = _propose(); // 0
        _pastWindow();
        oracle.claimProposerBond(a); // bond settled
        uint256 b = _propose(); // 1
        uint256 c = _propose(); // 2
        vm.prank(guardian);
        oracle.deleteFrom(c); // deletes 2 (refund)
        // delete from b again: c is already Deleted and skipped, no double refund
        vm.prank(guardian);
        oracle.deleteFrom(b);
        assertEq(
            oracle.credits(proposer),
            PROPOSER_BOND /*a*/ + 2 * PROPOSER_BOND /*b and c once each*/
        );
        // a is untouched
        assertEq(uint8(oracle.getOutput(a).status), uint8(OutputStatus.Proposed));
    }

    // ----- guardian emergency deletion -----

    function test_guardianDeletesProposedOutputWithoutConfiscatingBond() public {
        uint256 i = _propose();
        vm.expectEmit(true, false, false, false);
        emit OutputDeleted(i);
        vm.prank(guardian);
        oracle.deleteFrom(i);
        assertEq(uint8(oracle.getOutput(i).status), uint8(OutputStatus.Deleted));
        assertEq(oracle.credits(proposer), PROPOSER_BOND);
    }

    function test_guardianDeleteOfChallengedOutputActsAsUpheldChallenge() public {
        uint256 i = _challenged();
        vm.prank(guardian);
        oracle.deleteFrom(i);
        assertEq(oracle.credits(challenger), PROPOSER_BOND + CHALLENGER_BOND);
        assertEq(oracle.openChallenges(), 0);
    }

    function test_RevertWhen_guardianDeleteIsInvalid() public {
        uint256 i = _propose();
        vm.expectRevert(OutputOracle.NotGuardian.selector);
        oracle.deleteFrom(i);

        vm.startPrank(guardian);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.OutputNotFound.selector, 8));
        oracle.deleteFrom(8);
        oracle.deleteFrom(i);
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.WrongStatus.selector, OutputStatus.Deleted));
        oracle.deleteFrom(i);
        vm.stopPrank();
    }

    function test_RevertWhen_guardianDeletesAFinalOutput() public {
        uint256 i = _propose();
        _pastWindow();
        vm.expectRevert(OutputOracle.OutputAlreadyFinal.selector);
        vm.prank(guardian);
        oracle.deleteFrom(i);
    }

    function test_getOutputRevertsForUnknownIndex() public {
        vm.expectRevert(abi.encodeWithSelector(OutputOracle.OutputNotFound.selector, 0));
        oracle.getOutput(0);
    }
}

/// @dev A proposer that is a contract without `receive`: it can post bonds but cannot receive them back.
contract ContractProposer {
    OutputOracle internal immutable ORACLE;

    constructor(OutputOracle oracle) {
        ORACLE = oracle;
    }

    function propose(uint256 bond) external returns (uint256) {
        return ORACLE.proposeOutput{value: bond}(bytes32(uint256(1)), bytes32(uint256(2)), 1, 0);
    }

    function pull() external {
        ORACLE.withdrawCredits();
    }
}
