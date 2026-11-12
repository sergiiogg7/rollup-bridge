// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test} from "forge-std/Test.sol";
import {OutputOracle} from "../src/l1/OutputOracle.sol";
import {L1Bridge} from "../src/l1/L1Bridge.sol";
import {L2Bridge} from "../src/l2/L2Bridge.sol";
import {BridgedETH} from "../src/l2/BridgedETH.sol";
import {IOutputOracle} from "../src/interfaces/IOutputOracle.sol";
import {Withdrawal, WithdrawalHash} from "../src/lib/Withdrawals.sol";
import {TreeBuilder} from "./utils/TreeBuilder.sol";

/// @notice Both chains in one EVM: the L1 contracts and the L2 contracts are deployed side by side. That is
///         enough to test the contracts' logic; the real two-chain flow is covered by the Java E2E tests.
abstract contract BridgeFixture is Test {
    uint256 public constant WINDOW = 1 days;
    uint256 public constant RESOLUTION = 12 hours;
    uint256 public constant PROPOSER_BOND = 1 ether;
    uint256 public constant CHALLENGER_BOND = 0.5 ether;
    uint256 public constant MAX_DEPOSIT = 100 ether;
    uint256 public constant MAX_LOCKED = 1000 ether;

    address public owner = makeAddr("owner");
    address public proposer = makeAddr("proposer");
    address public guardian = makeAddr("guardian");
    address public relayer = makeAddr("relayer");
    address public challenger = makeAddr("challenger");
    address public alice = makeAddr("alice");
    address public bob = makeAddr("bob");

    OutputOracle public oracle;
    L1Bridge public l1;
    L2Bridge public l2;
    BridgedETH public token;

    Withdrawal[] internal ws;
    bytes32[] internal leaves;
    uint64 internal l2Block;

    function setUp() public virtual {
        oracle = new OutputOracle(owner, proposer, guardian, WINDOW, RESOLUTION, PROPOSER_BOND, CHALLENGER_BOND);
        l1 = new L1Bridge(owner, IOutputOracle(address(oracle)), MAX_DEPOSIT, MAX_LOCKED);
        l2 = new L2Bridge(owner, relayer);
        token = l2.TOKEN();
        vm.deal(proposer, 1000 ether);
        vm.deal(challenger, 1000 ether);
        vm.deal(alice, 1000 ether);
        vm.deal(bob, 1000 ether);
    }

    /// @dev Deposit on L1 and mirror it on L2 as the relayer would.
    function _deposit(address from, address to, uint256 amount) internal returns (uint256 id) {
        vm.prank(from);
        id = l1.depositETH{value: amount}(to);
        vm.prank(relayer);
        l2.finalizeDeposit(id, to, amount);
    }

    function _withdraw(address from, address target, uint256 amount) internal returns (Withdrawal memory w) {
        vm.prank(from);
        uint256 id = l2.initiateWithdrawal(target, amount);
        w = Withdrawal({id: id, sender: from, target: target, amount: amount});
        ws.push(w);
        leaves.push(WithdrawalHash.leaf(w));
    }

    /// @dev Honest proposal of the current L2 state.
    function _propose() internal returns (uint256 index) {
        ++l2Block;
        bytes32 root = l2.withdrawalRoot();
        uint64 count = uint64(l2.withdrawalCount());
        vm.prank(proposer);
        index =
            oracle.proposeOutput{value: PROPOSER_BOND}(root, keccak256(abi.encode("l2block", l2Block)), l2Block, count);
    }

    function _proof(uint256 id, uint256 count) internal view returns (bytes32[] memory) {
        return TreeBuilder.proof(TreeBuilder.head(leaves, count), id);
    }

    function _prove(Withdrawal memory w, uint256 outputIndex) internal {
        uint256 count = oracle.getOutput(outputIndex).withdrawalCount;
        l1.proveWithdrawal(w, outputIndex, _proof(w.id, count));
    }

    /// @dev Alice deposits `amount`, withdraws all of it to `target`, and the state is proposed.
    function _stage(address target, uint256 amount) internal returns (Withdrawal memory w, uint256 outputIndex) {
        _deposit(alice, alice, amount);
        w = _withdraw(alice, target, amount);
        outputIndex = _propose();
    }

    function _pastWindow() internal {
        skip(WINDOW);
    }
}
