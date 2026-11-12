// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {IERC20Errors} from "@openzeppelin/contracts/interfaces/draft-IERC6093.sol";
import {BridgeFixture} from "./Base.t.sol";
import {L2Bridge} from "../src/l2/L2Bridge.sol";
import {BridgedETH} from "../src/l2/BridgedETH.sol";
import {Withdrawal, WithdrawalHash} from "../src/lib/Withdrawals.sol";
import {TreeBuilder} from "./utils/TreeBuilder.sol";

contract L2BridgeTest is BridgeFixture {
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

    function test_constructorDeploysTokenOwnedByBridge() public view {
        assertEq(token.BRIDGE(), address(l2));
        assertEq(token.name(), "Bridged ETH");
        assertEq(token.symbol(), "bETH");
        assertEq(l2.relayer(), relayer);
        assertEq(l2.owner(), owner);
        assertEq(l2.withdrawalCount(), 0);
        assertEq(l2.withdrawalRoot(), TreeBuilder.zero(20));
    }

    function test_RevertWhen_constructorRelayerIsZero() public {
        vm.expectRevert(L2Bridge.ZeroAddress.selector);
        new L2Bridge(owner, address(0));
    }

    // ----- deposits -----

    function test_finalizeDepositMintsAndMarksProcessed() public {
        vm.expectEmit(true, true, false, true);
        emit DepositFinalized(7, alice, 3 ether);
        vm.prank(relayer);
        l2.finalizeDeposit(7, alice, 3 ether);
        assertEq(token.balanceOf(alice), 3 ether);
        assertEq(token.totalSupply(), 3 ether);
        assertTrue(l2.processedDeposits(7));
    }

    function test_RevertWhen_nonRelayerFinalizesDeposit() public {
        vm.expectRevert(L2Bridge.NotRelayer.selector);
        vm.prank(alice);
        l2.finalizeDeposit(0, alice, 1 ether);
    }

    function test_RevertWhen_depositReplayed() public {
        vm.startPrank(relayer);
        l2.finalizeDeposit(0, alice, 1 ether);
        vm.expectRevert(abi.encodeWithSelector(L2Bridge.DepositAlreadyProcessed.selector, 0));
        l2.finalizeDeposit(0, alice, 1 ether);
        vm.stopPrank();
        assertEq(token.totalSupply(), 1 ether);
    }

    // ----- withdrawals -----

    function test_initiateWithdrawalBurnsAndAppendsLeaf() public {
        _deposit(alice, alice, 5 ether);
        Withdrawal memory w = Withdrawal({id: 0, sender: alice, target: bob, amount: 2 ether});
        bytes32 leaf = WithdrawalHash.leaf(w);
        bytes32[] memory one = new bytes32[](1);
        one[0] = leaf;

        vm.expectEmit(true, true, true, true);
        emit WithdrawalInitiated(0, alice, bob, 2 ether, leaf, TreeBuilder.root(one));
        vm.prank(alice);
        assertEq(l2.initiateWithdrawal(bob, 2 ether), 0);

        assertEq(token.balanceOf(alice), 3 ether);
        assertEq(token.totalSupply(), 3 ether);
        assertEq(l2.withdrawalCount(), 1);
        assertEq(l2.withdrawalRoot(), TreeBuilder.root(one));
    }

    function test_withdrawalIdsAreSequentialAndRootTracksTree() public {
        _deposit(alice, alice, 10 ether);
        for (uint256 i = 0; i < 9; ++i) {
            Withdrawal memory w = _withdraw(alice, bob, 1 ether);
            assertEq(w.id, i);
            assertEq(l2.withdrawalRoot(), TreeBuilder.root(leaves));
        }
    }

    function test_RevertWhen_withdrawingMoreThanBalance() public {
        _deposit(alice, alice, 1 ether);
        vm.expectRevert(abi.encodeWithSelector(IERC20Errors.ERC20InsufficientBalance.selector, alice, 1 ether, 2 ether));
        vm.prank(alice);
        l2.initiateWithdrawal(bob, 2 ether);
    }

    function test_RevertWhen_withdrawalTargetIsZero() public {
        vm.expectRevert(L2Bridge.ZeroAddress.selector);
        l2.initiateWithdrawal(address(0), 1);
    }

    function test_RevertWhen_withdrawalAmountIsZero() public {
        vm.expectRevert(L2Bridge.ZeroAmount.selector);
        l2.initiateWithdrawal(bob, 0);
    }

    // ----- admin -----

    function test_ownerCanRotateRelayer() public {
        vm.expectEmit(true, false, false, false);
        emit RelayerUpdated(bob);
        vm.prank(owner);
        l2.setRelayer(bob);
        assertEq(l2.relayer(), bob);
    }

    function test_RevertWhen_setRelayerByNonOwner() public {
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, alice));
        vm.prank(alice);
        l2.setRelayer(bob);
    }

    function test_RevertWhen_setRelayerZero() public {
        vm.expectRevert(L2Bridge.ZeroAddress.selector);
        vm.prank(owner);
        l2.setRelayer(address(0));
    }

    // ----- token -----

    function test_RevertWhen_tokenMintedOrBurnedByNonBridge() public {
        vm.expectRevert(BridgedETH.NotBridge.selector);
        token.mint(alice, 1);
        vm.expectRevert(BridgedETH.NotBridge.selector);
        token.burn(alice, 1);
    }

    function testFuzz_supplyEqualsDepositsMinusWithdrawals(uint96 deposit, uint96 withdrawal) public {
        deposit = uint96(bound(deposit, 1, MAX_DEPOSIT));
        withdrawal = uint96(bound(withdrawal, 1, deposit));
        _deposit(alice, alice, deposit);
        _withdraw(alice, bob, withdrawal);
        assertEq(token.totalSupply(), uint256(deposit) - withdrawal);
    }
}
