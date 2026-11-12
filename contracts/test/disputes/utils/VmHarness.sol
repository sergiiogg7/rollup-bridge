// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {ToyVM, VMState} from "../../../src/disputes/ToyVM.sol";

/// @notice Exposes the internal `ToyVM` library as external pure functions so the Java differential tests can
///         call the REAL Solidity `step` and `hashState` over JSON-RPC (`eth_call`) with thousands of random inputs.
contract VmHarness {
    function step(bytes calldata program, VMState calldata s) external pure returns (VMState memory) {
        return ToyVM.step(program, s);
    }

    function hashState(VMState calldata s) external pure returns (bytes32) {
        return ToyVM.hashState(s);
    }

    function isWellFormed(bytes calldata program) external pure returns (bool) {
        return ToyVM.isWellFormed(program);
    }
}
