// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Script, console} from "forge-std/Script.sol";
import {OutputOracle} from "../src/l1/OutputOracle.sol";
import {L1Bridge} from "../src/l1/L1Bridge.sol";
import {IOutputOracle} from "../src/interfaces/IOutputOracle.sol";

/// @notice Deploys `OutputOracle` and `L1Bridge` on the L1 chain and writes `deployments/l1.json`.
/// @dev Env: PRIVATE_KEY (deployer, also the owner unless OWNER is set), PROPOSER, GUARDIAN.
///      Optional: CHALLENGE_WINDOW (s, default 7 days), RESOLUTION_PERIOD (s, default 3 days),
///      PROPOSER_BOND (wei, default 1 ether), CHALLENGER_BOND (wei, default 0.5 ether),
///      MAX_DEPOSIT (wei, default 10 ether), MAX_TOTAL_LOCKED (wei, default 100 ether).
contract DeployL1 is Script {
    struct Params {
        address owner;
        address proposer;
        address guardian;
        uint256 window;
        uint256 resolution;
        uint256 proposerBond;
        uint256 challengerBond;
        uint256 maxDeposit;
        uint256 maxLocked;
    }

    function params() public view returns (Params memory p) {
        address deployer = vm.addr(vm.envUint("PRIVATE_KEY"));
        p.owner = vm.envOr("OWNER", deployer);
        p.proposer = vm.envAddress("PROPOSER");
        p.guardian = vm.envAddress("GUARDIAN");
        p.window = vm.envOr("CHALLENGE_WINDOW", uint256(7 days));
        p.resolution = vm.envOr("RESOLUTION_PERIOD", uint256(3 days));
        p.proposerBond = vm.envOr("PROPOSER_BOND", uint256(1 ether));
        p.challengerBond = vm.envOr("CHALLENGER_BOND", uint256(0.5 ether));
        p.maxDeposit = vm.envOr("MAX_DEPOSIT", uint256(10 ether));
        p.maxLocked = vm.envOr("MAX_TOTAL_LOCKED", uint256(100 ether));
    }

    function run() external returns (OutputOracle oracle, L1Bridge bridge) {
        Params memory p = params();
        vm.startBroadcast(vm.envUint("PRIVATE_KEY"));
        (oracle, bridge) = deploy(p);
        vm.stopBroadcast();
        write(oracle, bridge, "../deployments/l1.json");
    }

    /// @dev Shared by `LocalDeploy`; must run inside a broadcast.
    function deploy(Params memory p) public returns (OutputOracle oracle, L1Bridge bridge) {
        oracle =
            new OutputOracle(p.owner, p.proposer, p.guardian, p.window, p.resolution, p.proposerBond, p.challengerBond);
        bridge = new L1Bridge(p.owner, IOutputOracle(address(oracle)), p.maxDeposit, p.maxLocked);
    }

    function write(OutputOracle oracle, L1Bridge bridge, string memory path) public {
        console.log("OutputOracle:", address(oracle));
        console.log("L1Bridge:    ", address(bridge));
        string memory json = string.concat(
            '{"outputOracle":"', vm.toString(address(oracle)), '","l1Bridge":"', vm.toString(address(bridge)), '"}\n'
        );
        vm.writeFile(path, json);
    }
}
