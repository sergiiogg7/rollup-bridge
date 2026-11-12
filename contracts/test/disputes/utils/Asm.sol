// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {ToyVM, VMState} from "../../../src/disputes/ToyVM.sol";

/// @notice Tiny assembler and tracer for the tests: builds ToyVM programs and computes the TRUE execution trace
///         (the honest participant's knowledge in the game tests).
library Asm {
    uint8 internal constant HALT = 0;
    uint8 internal constant MOV = 1;
    uint8 internal constant ADD = 2;
    uint8 internal constant SUB = 3;
    uint8 internal constant MUL = 4;
    uint8 internal constant JNZ = 5;

    function ins(uint8 op, uint8 a, uint8 b, uint64 imm) internal pure returns (bytes memory) {
        return abi.encodePacked(op, a, b, imm);
    }

    function mov(uint8 a, uint64 imm) internal pure returns (bytes memory) {
        return ins(MOV, a, 0, imm);
    }

    function add(uint8 a, uint8 b) internal pure returns (bytes memory) {
        return ins(ADD, a, b, 0);
    }

    function sub(uint8 a, uint8 b) internal pure returns (bytes memory) {
        return ins(SUB, a, b, 0);
    }

    function mul(uint8 a, uint8 b) internal pure returns (bytes memory) {
        return ins(MUL, a, b, 0);
    }

    function jnz(uint8 a, uint64 target) internal pure returns (bytes memory) {
        return ins(JNZ, a, 0, target);
    }

    function halt() internal pure returns (bytes memory) {
        return ins(HALT, 0, 0, 0);
    }

    /// @dev r1 = n + (n-1) + ... + 1, then HALT. Takes 3 + 3n steps to reach HALT (and stays there).
    function sumLoop(uint64 n) internal pure returns (bytes memory) {
        return bytes.concat(
            bytes.concat(mov(0, n), mov(1, 0), mov(2, 1)), bytes.concat(add(1, 0), sub(0, 2), jnz(0, 3), halt())
        );
    }

    /// @dev r1 = n! (mod 2^64), then HALT.
    function factorial(uint64 n) internal pure returns (bytes memory) {
        return bytes.concat(
            bytes.concat(mov(0, n), mov(1, 1), mov(2, 1)), bytes.concat(mul(1, 0), sub(0, 2), jnz(0, 3), halt())
        );
    }

    function zeroState() internal pure returns (VMState memory s) {
        s = VMState({pc: 0, regs: [uint64(0), 0, 0, 0]});
    }

    /// @dev The state after `n` steps from `init`.
    function run(bytes memory program, VMState memory init, uint256 n) internal pure returns (VMState memory s) {
        s = init;
        for (uint256 i = 0; i < n; ++i) {
            s = ToyVM.step(program, s);
        }
    }

    /// @dev `trace[i]` = state at step i, for i in 0..n.
    function trace(bytes memory program, VMState memory init, uint256 n) internal pure returns (VMState[] memory t) {
        t = new VMState[](n + 1);
        t[0] = init;
        for (uint256 i = 0; i < n; ++i) {
            t[i + 1] = ToyVM.step(program, t[i]);
        }
    }

    function hashes(VMState[] memory t) internal pure returns (bytes32[] memory h) {
        h = new bytes32[](t.length);
        for (uint256 i = 0; i < t.length; ++i) {
            h[i] = ToyVM.hashState(t[i]);
        }
    }
}
