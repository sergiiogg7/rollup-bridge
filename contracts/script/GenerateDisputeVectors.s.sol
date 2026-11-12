// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Script} from "forge-std/Script.sol";
import {ToyVM, VMState} from "../src/disputes/ToyVM.sol";
import {Asm} from "../test/disputes/utils/Asm.sol";

/// @notice Writes `testdata/dispute-vectors.json`, the shared vectors for the Solidity and Java toy VMs:
///         (1) `stepCases`: a program, a pre-state and the post-state computed by the REAL `ToyVM.step`, for
///             pseudo-random programs that include every opcode, invalid opcodes, invalid registers and
///             out-of-range program counters (so the "trap" rules are pinned down);
///         (2) `traces`: whole executions of two known programs with the state hash after every step.
///         Everything is derived from `keccak256` of a counter, so the file is reproducible.
///         Run: `forge script script/GenerateDisputeVectors.s.sol` (from `contracts/`).
contract GenerateDisputeVectors is Script {
    uint256 internal constant SHORT_CASES = 400;
    uint256 internal constant LONG_CASES = 24;

    function run() external {
        string memory path = "../testdata/dispute-vectors.json";
        // One case per line, appended line by line: building the whole file as one string is quadratic in memory.
        vm.writeFile(path, "{");
        vm.writeLine(path, "  \"stepCases\": [");
        uint256 total = SHORT_CASES + LONG_CASES;
        for (uint256 i = 0; i < total; ++i) {
            uint256 maxLen = i < SHORT_CASES ? 8 : 64;
            vm.writeLine(path, string.concat("    ", _stepCase(i, maxLen), i + 1 < total ? "," : ""));
        }
        vm.writeLine(path, "  ],");
        vm.writeLine(path, "  \"traces\": [");
        vm.writeLine(path, string.concat("    ", _trace("sum-loop(5)", Asm.sumLoop(5), 40), ","));
        vm.writeLine(path, string.concat("    ", _trace("factorial(12)", Asm.factorial(12), 60)));
        vm.writeLine(path, "  ]");
        vm.writeLine(path, "}");
    }

    // ----- random step cases -----

    function _rand(uint256 i, string memory tag) internal pure returns (uint256) {
        return uint256(keccak256(abi.encode(i, tag)));
    }

    /// @dev Biased 64-bit value: often small, sometimes the extremes, otherwise uniform.
    function _word(uint256 i, string memory tag) internal pure returns (uint64) {
        uint256 r = _rand(i, tag);
        uint256 kind = r % 8;
        // forge-lint: disable-start(unsafe-typecast)
        if (kind == 0) return 0;
        if (kind == 1) return type(uint64).max;
        if (kind < 5) return uint64((r >> 8) % 16);
        return uint64(r >> 16);
        // forge-lint: disable-end(unsafe-typecast)
    }

    function _program(uint256 i, uint256 maxLen) internal pure returns (bytes memory p) {
        uint256 len = 1 + _rand(i, "len") % maxLen;
        for (uint256 k = 0; k < len; ++k) {
            uint256 r = _rand(i * 1000 + k, "ins");
            // mostly real opcodes 1..5; now and then HALT (0) or an invalid opcode (6, 7); registers mostly 0..3, now
            // and then an invalid index (4, 5)
            // forge-lint: disable-start(unsafe-typecast)
            uint8 op = r % 16 == 0 ? 0 : (r % 16 == 1 ? uint8(6 + (r >> 4) % 2) : uint8(1 + (r >> 4) % 5));
            uint8 a = uint8((r >> 8) % 16 < 6 ? (r >> 8) % 16 : (r >> 8) % 4);
            uint8 b = uint8((r >> 16) % 16 < 6 ? (r >> 16) % 16 : (r >> 16) % 4);
            uint64 imm = op == Asm.JNZ ? uint64((r >> 24) % (len + 2)) : _word(i * 1000 + k, "imm");
            // forge-lint: disable-end(unsafe-typecast)
            p = bytes.concat(p, Asm.ins(op, a, b, imm));
        }
    }

    function _stepCase(uint256 i, uint256 maxLen) internal pure returns (string memory) {
        bytes memory program = _program(i, maxLen);
        VMState memory pre;
        // pc mostly inside the program, sometimes just past the end or far away
        uint256 len = program.length / ToyVM.INSTRUCTION_SIZE;
        uint256 r = _rand(i, "pc");
        // forge-lint: disable-next-line(unsafe-typecast)
        pre.pc = r % 16 == 0 ? uint64(r >> 8) : (r % 16 == 1 ? uint64(len + (r >> 8) % 2) : uint64((r >> 8) % len));
        for (uint256 k = 0; k < 4; ++k) {
            pre.regs[k] = _word(i * 10 + k, "reg");
        }
        VMState memory post = ToyVM.step(program, pre);
        return string.concat(
            '{"program":"',
            vm.toString(program),
            '","pre":',
            _stateJson(pre),
            ',"post":',
            _stateJson(post),
            ',"preHash":"',
            vm.toString(ToyVM.hashState(pre)),
            '","postHash":"',
            vm.toString(ToyVM.hashState(post)),
            '"}'
        );
    }

    // ----- full traces -----

    function _trace(string memory name, bytes memory program, uint256 steps) internal pure returns (string memory) {
        VMState memory s = Asm.zeroState();
        string memory hashes = string.concat('"', vm.toString(ToyVM.hashState(s)), '"');
        for (uint256 i = 0; i < steps; ++i) {
            s = ToyVM.step(program, s);
            hashes = string.concat(hashes, ',"', vm.toString(ToyVM.hashState(s)), '"');
        }
        return string.concat(
            '{"name":"',
            name,
            '","program":"',
            vm.toString(program),
            '","steps":',
            vm.toString(steps),
            ',"final":',
            _stateJson(s),
            ',"hashes":[',
            hashes,
            "]}"
        );
    }

    function _stateJson(VMState memory s) internal pure returns (string memory) {
        return string.concat(
            '{"pc":"',
            vm.toString(uint256(s.pc)),
            '","regs":["',
            vm.toString(uint256(s.regs[0])),
            '","',
            vm.toString(uint256(s.regs[1])),
            '","',
            vm.toString(uint256(s.regs[2])),
            '","',
            vm.toString(uint256(s.regs[3])),
            '"]}'
        );
    }
}
