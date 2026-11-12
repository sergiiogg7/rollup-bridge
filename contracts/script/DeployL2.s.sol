// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Script, console} from "forge-std/Script.sol";
import {L2Bridge} from "../src/l2/L2Bridge.sol";

/// @notice Deploys `L2Bridge` (which deploys `BridgedETH`) on the L2 chain and writes `deployments/l2.json`.
/// @dev Env: PRIVATE_KEY (deployer, also the owner unless OWNER is set), RELAYER (deposit relayer account).
contract DeployL2 is Script {
    function run() external returns (L2Bridge bridge) {
        address deployer = vm.addr(vm.envUint("PRIVATE_KEY"));
        address owner = vm.envOr("OWNER", deployer);
        address relayer = vm.envAddress("RELAYER");
        vm.startBroadcast(vm.envUint("PRIVATE_KEY"));
        bridge = deploy(owner, relayer);
        vm.stopBroadcast();
        write(bridge, "../deployments/l2.json");
    }

    /// @dev Shared by `LocalDeploy`; must run inside a broadcast.
    function deploy(address owner, address relayer) public returns (L2Bridge bridge) {
        bridge = new L2Bridge(owner, relayer);
    }

    function write(L2Bridge bridge, string memory path) public {
        console.log("L2Bridge:   ", address(bridge));
        console.log("BridgedETH: ", address(bridge.TOKEN()));
        string memory json = string.concat(
            '{"l2Bridge":"',
            vm.toString(address(bridge)),
            '","bridgedEth":"',
            vm.toString(address(bridge.TOKEN())),
            '"}\n'
        );
        vm.writeFile(path, json);
    }
}
