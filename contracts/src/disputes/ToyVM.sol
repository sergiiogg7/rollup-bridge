// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

/// @notice The whole machine state: a program counter and four 64-bit registers.
struct VMState {
    uint64 pc;
    uint64[4] regs;
}

/// @title ToyVM
/// @notice A deliberately tiny deterministic machine, the "program" of the bisection dispute game
///         (see `BisectionGame` and docs/DISPUTE-GAME.md). It exists so that ONE step of execution is cheap
///         to run on-chain; a production fault-proof system does the same with a MIPS or RISC-V machine.
///
///         Program format: at most `MAX_INSTRUCTIONS` instructions of exactly `INSTRUCTION_SIZE` bytes,
///         concatenated: `op (1 byte) | a (1 byte) | b (1 byte) | imm (8 bytes, big endian)`.
///
///         | op | name | effect                                                     |
///         |----|------|------------------------------------------------------------|
///         | 0  | HALT | nothing (the state is a fixed point)                       |
///         | 1  | MOV  | `r[a] = imm`, `pc += 1`                                    |
///         | 2  | ADD  | `r[a] = r[a] + r[b]` (mod 2^64), `pc += 1`                 |
///         | 3  | SUB  | `r[a] = r[a] - r[b]` (mod 2^64), `pc += 1`                 |
///         | 4  | MUL  | `r[a] = r[a] * r[b]` (mod 2^64), `pc += 1`                 |
///         | 5  | JNZ  | `pc = imm` if `r[a] != 0`, otherwise `pc += 1`             |
///
///         `step` is a TOTAL function: it never reverts. A state whose `pc` is past the end of the program, an
///         unknown opcode and a register index above 3 all behave like HALT (the state does not change). This
///         matters for a dispute game: the contested step must always be executable, otherwise a program with
///         a bad instruction could freeze a game. The state hash is `keccak256(abi.encode(pc, r0, r1, r2, r3))`
///         (five 32-byte words); the Java reference VM implements exactly the same rules.
library ToyVM {
    uint256 internal constant INSTRUCTION_SIZE = 11;
    uint256 internal constant MAX_INSTRUCTIONS = 64;
    uint256 internal constant NUM_REGISTERS = 4;

    uint8 internal constant OP_HALT = 0;
    uint8 internal constant OP_MOV = 1;
    uint8 internal constant OP_ADD = 2;
    uint8 internal constant OP_SUB = 3;
    uint8 internal constant OP_MUL = 4;
    uint8 internal constant OP_JNZ = 5;

    /// @notice Commitment to a state: `keccak256(abi.encode(pc, r0, r1, r2, r3))`.
    function hashState(VMState memory s) internal pure returns (bytes32) {
        // A static array is encoded inline, so this is exactly five 32-byte words.
        return keccak256(abi.encode(s.pc, s.regs));
    }

    /// @notice True if `program` is well formed: a whole number of instructions, between 1 and 64 of them.
    ///         (`step` does not need this to hold; it is a check for program authors, who commit to a program
    ///         by hash and should validate it off-chain before anybody bonds money on it.)
    function isWellFormed(bytes memory program) internal pure returns (bool) {
        return program.length != 0 && program.length % INSTRUCTION_SIZE == 0
            && program.length <= INSTRUCTION_SIZE * MAX_INSTRUCTIONS;
    }

    /// @notice Executes exactly one instruction. Pure and total (see the contract-level notes).
    function step(bytes memory program, VMState memory s) internal pure returns (VMState memory next) {
        next = VMState({pc: s.pc, regs: [s.regs[0], s.regs[1], s.regs[2], s.regs[3]]});

        uint256 count = program.length / INSTRUCTION_SIZE;
        // `pc` is a uint64 and `count` is tiny, so the comparison cannot overflow.
        if (s.pc >= count) return next;

        uint256 base = uint256(s.pc) * INSTRUCTION_SIZE;
        uint8 op = uint8(program[base]);
        uint8 a = uint8(program[base + 1]);
        uint8 b = uint8(program[base + 2]);
        uint64 imm = _immediate(program, base + 3);

        if (op == OP_HALT) return next;
        if (a >= NUM_REGISTERS) return next; // trap: behaves like HALT

        if (op == OP_MOV) {
            next.regs[a] = imm;
        } else if (op == OP_JNZ) {
            if (s.regs[a] != 0) {
                next.pc = imm;
                return next;
            }
        } else if (op == OP_ADD || op == OP_SUB || op == OP_MUL) {
            if (b >= NUM_REGISTERS) return next; // trap
            uint64 x = s.regs[a];
            uint64 y = s.regs[b];
            unchecked {
                if (op == OP_ADD) {
                    next.regs[a] = x + y;
                } else if (op == OP_SUB) {
                    next.regs[a] = x - y;
                } else {
                    next.regs[a] = x * y;
                }
            }
        } else {
            return next; // unknown opcode: trap
        }
        next.pc = s.pc + 1;
    }

    /// @dev Big-endian uint64 at `program[offset .. offset + 8)`.
    function _immediate(bytes memory program, uint256 offset) private pure returns (uint64 imm) {
        for (uint256 i = 0; i < 8; ++i) {
            imm = (imm << 8) | uint64(uint8(program[offset + i]));
        }
    }
}
