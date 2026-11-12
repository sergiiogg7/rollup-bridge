// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Script, console} from "forge-std/Script.sol";
import {OutputOracle} from "../src/l1/OutputOracle.sol";
import {L1Bridge} from "../src/l1/L1Bridge.sol";
import {L2Bridge} from "../src/l2/L2Bridge.sol";
import {IOutputOracle} from "../src/interfaces/IOutputOracle.sol";

/// @notice Deploys one side of the bridge on a local Anvil chain with the standard PUBLIC Anvil accounts and a
///         short lab challenge window, so demos finish in seconds. TEST KEYS ONLY: never use them on a real network.
///         Roles: account 0 deployer/owner, 1 relayer, 2 proposer, 4 guardian.
///
///         forge script script/LocalDeploy.s.sol --tc LocalDeploy --sig "runL1()" --rpc-url http://127.0.0.1:8545 --broadcast
///         forge script script/LocalDeploy.s.sol --tc LocalDeploy --sig "runL2()" --rpc-url http://127.0.0.1:9545 --broadcast
///
/// @dev Two entry points instead of one multi-chain script: Foundry's multi-chain broadcast is still marked as
///      experimental, and one chain per run keeps the output files (`deployments/l1.json`, `l2.json`) simple.
contract LocalDeploy is Script {
    uint256 internal constant DEPLOYER_KEY = 0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80;
    address internal constant RELAYER = 0x70997970C51812dc3A010C7d01b50e0d17dc79C8;
    address internal constant PROPOSER = 0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC;
    address internal constant GUARDIAN = 0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65;

    uint256 internal constant LAB_WINDOW = 120; // seconds
    uint256 internal constant LAB_RESOLUTION = 600; // seconds

    function runL1() external returns (OutputOracle oracle, L1Bridge bridge) {
        address owner = vm.addr(DEPLOYER_KEY);
        vm.startBroadcast(DEPLOYER_KEY);
        oracle = new OutputOracle(owner, PROPOSER, GUARDIAN, LAB_WINDOW, LAB_RESOLUTION, 1 ether, 0.5 ether);
        bridge = new L1Bridge(owner, IOutputOracle(address(oracle)), 10 ether, 100 ether);
        vm.stopBroadcast();

        console.log("OutputOracle:", address(oracle));
        console.log("L1Bridge:    ", address(bridge));
        vm.writeFile(
            "../deployments/l1.json",
            string.concat(
                '{"outputOracle":"',
                vm.toString(address(oracle)),
                '","l1Bridge":"',
                vm.toString(address(bridge)),
                '","deployBlock":',
                vm.toString(block.number),
                "}\n"
            )
        );
    }

    function runL2() external returns (L2Bridge bridge) {
        address owner = vm.addr(DEPLOYER_KEY);
        vm.startBroadcast(DEPLOYER_KEY);
        bridge = new L2Bridge(owner, RELAYER);
        vm.stopBroadcast();

        console.log("L2Bridge:   ", address(bridge));
        console.log("BridgedETH: ", address(bridge.TOKEN()));
        vm.writeFile(
            "../deployments/l2.json",
            string.concat(
                '{"l2Bridge":"',
                vm.toString(address(bridge)),
                '","bridgedEth":"',
                vm.toString(address(bridge.TOKEN())),
                '"}\n'
            )
        );
    }
}
