// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test} from "forge-std/Test.sol";
import {BridgeFixture} from "../Base.t.sol";
import {OutputOracle} from "../../src/l1/OutputOracle.sol";
import {Output, OutputStatus} from "../../src/interfaces/IOutputOracle.sol";
import {Withdrawal, WithdrawalHash} from "../../src/lib/Withdrawals.sol";
import {TreeBuilder} from "../utils/TreeBuilder.sol";
import {RevertingTarget} from "../utils/Targets.sol";

/// @notice Random sequences of every public action of the bridge (both chains in one EVM). Ghost variables
///         track what the contracts SHOULD hold; the invariants compare them with reality.
contract BridgeHandler is BridgeFixture {
    address[] internal users;
    address[] internal targets;
    address internal rejecting;

    // ghost state
    uint256 public ghostDeposited; // wei locked on L1 via depositETH
    uint256 public ghostPaidDirect; // wei sent to targets by finalizeWithdrawal
    uint256 public ghostClaimed; // wei sent by claim()
    uint256 public ghostFinalizedAmount; // sum of amounts of finalized withdrawals
    uint256 public ghostRelayedToL2; // sum of minted deposits
    uint256 public ghostBurnedOnL2; // sum of withdrawn amounts
    uint256 public ghostEarlyFinalizations; // finalized while NOT finalizable (must stay 0)
    uint256 public ghostDoubleFinalizations; // same leaf finalized twice (must stay 0)
    uint256 public ghostCountShrank; // withdrawalCount decreased (must stay 0)
    uint256 public lastCount;
    uint256 public stats_finalized;
    uint256 public stats_credited;
    uint256 public stats_proven;
    uint256 public stats_deleted;
    mapping(bytes32 => uint256) public finalizeCount;

    function setUp() public override {
        super.setUp();
        users.push(alice);
        users.push(bob);
        rejecting = address(new RevertingTarget());
        targets.push(alice);
        targets.push(bob);
        targets.push(rejecting);
        targets.push(makeAddr("carol"));
        // fund the actors already done in the fixture
    }

    // ----- actions -----

    function deposit(uint256 userSeed, uint256 amount) external {
        amount = bound(amount, 1, MAX_DEPOSIT);
        address user = users[userSeed % users.length];
        uint256 id;
        vm.deal(user, user.balance + amount);
        vm.prank(user);
        try l1.depositETH{value: amount}(user) returns (uint256 depositId) {
            id = depositId;
        } catch {
            return;
        }
        ghostDeposited += amount;
        vm.prank(relayer);
        l2.finalizeDeposit(id, user, amount);
        ghostRelayedToL2 += amount;
    }

    function withdraw(uint256 userSeed, uint256 targetSeed, uint256 amount) external {
        address user = users[userSeed % users.length];
        uint256 bal = token.balanceOf(user);
        if (bal == 0) return;
        amount = bound(amount, 1, bal);
        _withdraw(user, targets[targetSeed % targets.length], amount);
        ghostBurnedOnL2 += amount;
        if (l2.withdrawalCount() < lastCount) ++ghostCountShrank;
        lastCount = l2.withdrawalCount();
    }

    function propose() external {
        if (oracle.outputCount() > 0) {
            (bool found, uint256 i) = oracle.latestLive();
            if (found && oracle.getOutput(i).withdrawalCount == l2.withdrawalCount()) return;
        }
        _propose();
    }

    function prove(uint256 wSeed, uint256 outSeed) external {
        if (ws.length == 0 || oracle.outputCount() == 0) return;
        Withdrawal memory w = ws[wSeed % ws.length];
        uint256 out = outSeed % oracle.outputCount();
        Output memory o = oracle.getOutput(out);
        if (w.id >= o.withdrawalCount) return;
        try l1.proveWithdrawal(w, out, _proof(w.id, o.withdrawalCount)) {
            ++stats_proven;
        } catch {}
    }

    function finalize(uint256 wSeed) external {
        if (ws.length == 0) return;
        Withdrawal memory w = ws[wSeed % ws.length];
        bytes32 leaf = WithdrawalHash.leaf(w);
        (uint64 outIdx,, bool exists) = l1.proofs(leaf);
        bool shouldBeAllowed = exists && oracle.isFinalizable(outIdx) && !l1.finalized(leaf) && !l1.paused()
            && w.amount <= l1.totalLocked();
        uint256 targetBefore = w.target.balance;
        uint256 claimableBefore = l1.claimable(w.target);
        try l1.finalizeWithdrawal(w) {
            if (!shouldBeAllowed) ++ghostEarlyFinalizations;
            if (++finalizeCount[leaf] > 1) ++ghostDoubleFinalizations;
            ghostFinalizedAmount += w.amount;
            ++stats_finalized;
            if (l1.claimable(w.target) == claimableBefore) {
                ghostPaidDirect += w.amount;
            } else {
                ++stats_credited;
                assertEq(w.target.balance, targetBefore, "credited but also paid");
            }
        } catch {}
    }

    function claim(uint256 targetSeed) external {
        address t = targets[targetSeed % targets.length];
        uint256 c = l1.claimable(t);
        vm.prank(t);
        try l1.claim() {
            ghostClaimed += c;
        } catch {}
    }

    function challenge(uint256 outSeed) external {
        if (oracle.outputCount() == 0) return;
        vm.prank(challenger);
        try oracle.challengeOutput{value: CHALLENGER_BOND}(outSeed % oracle.outputCount()) {} catch {}
    }

    function resolve(uint256 outSeed, bool proposerWasWrong) external {
        if (oracle.outputCount() == 0) return;
        vm.prank(guardian);
        try oracle.resolveChallenge(outSeed % oracle.outputCount(), proposerWasWrong) {} catch {}
    }

    function expire(uint256 outSeed) external {
        if (oracle.outputCount() == 0) return;
        try oracle.expireChallenge(outSeed % oracle.outputCount()) {} catch {}
    }

    function guardianDelete(uint256 outSeed) external {
        if (oracle.outputCount() == 0) return;
        vm.prank(guardian);
        try oracle.deleteFrom(outSeed % oracle.outputCount()) {} catch {}
    }

    function claimBond(uint256 outSeed) external {
        if (oracle.outputCount() == 0) return;
        try oracle.claimProposerBond(outSeed % oracle.outputCount()) {} catch {}
    }

    function withdrawCredits(bool forChallenger) external {
        vm.prank(forChallenger ? challenger : proposer);
        try oracle.withdrawCredits() {} catch {}
    }

    function pause(bool on) external {
        vm.prank(guardian);
        if (on) l1.pause();
        else l1.unpause();
    }

    function warp(uint256 secs) external {
        skip(bound(secs, 1, WINDOW));
    }

    // ----- views for invariants -----

    function outputsLength() external view returns (uint256) {
        return oracle.outputCount();
    }

    function usersLength() external view returns (uint256) {
        return users.length;
    }

    function userAt(uint256 i) external view returns (address) {
        return users[i];
    }
}

contract BridgeInvariantTest is Test {
    BridgeHandler internal h;

    function setUp() public {
        h = new BridgeHandler();
        h.setUp();
        targetContract(address(h));
        bytes4[] memory selectors = new bytes4[](13);
        selectors[0] = h.deposit.selector;
        selectors[1] = h.withdraw.selector;
        selectors[2] = h.propose.selector;
        selectors[3] = h.prove.selector;
        selectors[4] = h.finalize.selector;
        selectors[5] = h.claim.selector;
        selectors[6] = h.challenge.selector;
        selectors[7] = h.resolve.selector;
        selectors[8] = h.guardianDelete.selector;
        selectors[9] = h.claimBond.selector;
        selectors[10] = h.warp.selector;
        selectors[11] = h.pause.selector;
        selectors[12] = h.expire.selector;
        targetSelector(FuzzSelector({addr: address(h), selectors: selectors}));
    }

    /// @dev Solvency: the L1 bridge holds exactly what was deposited minus what it paid out.
    function invariant_L1Solvency() public view {
        uint256 bal = address(h.l1()).balance;
        assertEq(bal, h.ghostDeposited() - h.ghostPaidDirect() - h.ghostClaimed(), "balance != deposited - paid");
        assertGe(bal, h.l1().totalLocked() + h.l1().totalClaimable(), "balance < liabilities");
        assertEq(bal, h.l1().totalLocked() + h.l1().totalClaimable(), "balance != liabilities");
    }

    /// @dev What L2 holders can still claim equals deposits minus finalized withdrawals.
    function invariant_lockedEqualsDepositedMinusFinalized() public view {
        assertEq(h.l1().totalLocked(), h.ghostDeposited() - h.ghostFinalizedAmount());
    }

    function invariant_noDoubleFinalize() public view {
        assertEq(h.ghostDoubleFinalizations(), 0);
    }

    function invariant_nothingFinalizesEarlyOrWhileChallenged() public view {
        assertEq(h.ghostEarlyFinalizations(), 0);
    }

    /// @dev Supply conservation on L2.
    function invariant_L2SupplyConservation() public view {
        assertEq(h.token().totalSupply(), h.ghostRelayedToL2() - h.ghostBurnedOnL2());
    }

    function invariant_withdrawalCountOnlyGrows() public view {
        assertEq(h.ghostCountShrank(), 0);
        assertGe(h.l2().withdrawalCount(), h.lastCount());
    }

    /// @dev The oracle's balance is always exactly the bonds it still owes: unsettled proposer bonds, challenger
    ///      bonds of open challenges and pull-payment credits.
    function invariant_oracleBondConservation() public view {
        OutputOracle o = h.oracle();
        uint256 owed = o.credits(h.proposer()) + o.credits(h.challenger());
        uint256 n = o.outputCount();
        uint256 open;
        for (uint256 i = 0; i < n; ++i) {
            Output memory out = o.getOutput(i);
            if (!out.bondSettled) owed += h.PROPOSER_BOND();
            if (out.status == OutputStatus.Challenged) {
                owed += h.CHALLENGER_BOND();
                ++open;
            }
        }
        assertEq(address(o).balance, owed, "bonds");
        assertEq(o.openChallenges(), open, "open challenge counter");
    }
}

contract HandlerSmokeTest is Test {
    function test_handlerCanDriveAWithdrawalToFinalization() public {
        BridgeHandler h = new BridgeHandler();
        h.setUp();
        h.deposit(0, 5 ether);
        h.withdraw(0, 1, 2 ether);
        h.propose();
        h.prove(0, 0);
        h.warp(1 days);
        h.finalize(0);
        assertEq(h.stats_proven(), 1);
        assertEq(h.stats_finalized(), 1);
        assertEq(h.ghostPaidDirect(), 2 ether);
    }
}
