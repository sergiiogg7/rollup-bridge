package dev.rollupbridge.dispute.vm;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Keccak256;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A toy-VM program: instructions of exactly {@value #INSTRUCTION_SIZE} bytes, concatenated
 * ({@code op | a | b | imm}, the immediate is a big-endian 64-bit value). The program is committed on-chain by its
 * hash, {@code keccak256(code)}.
 */
public final class Program {
    public static final int INSTRUCTION_SIZE = 11;
    public static final int MAX_INSTRUCTIONS = 64;

    public static final int HALT = 0;
    public static final int MOV = 1;
    public static final int ADD = 2;
    public static final int SUB = 3;
    public static final int MUL = 4;
    public static final int JNZ = 5;

    private final byte[] code;

    private Program(byte[] code) {
        this.code = code;
    }

    /** One instruction; {@code op}, {@code a} and {@code b} are raw bytes so that INVALID instructions can be built too. */
    public record Instruction(int op, int a, int b, long imm) {
        public Instruction {
            if ((op | a | b) < 0 || op > 255 || a > 255 || b > 255) {
                throw new IllegalArgumentException("op, a and b must be bytes");
            }
        }

        public static Instruction halt() {
            return new Instruction(HALT, 0, 0, 0);
        }

        public static Instruction mov(int a, long imm) {
            return new Instruction(MOV, a, 0, imm);
        }

        public static Instruction add(int a, int b) {
            return new Instruction(ADD, a, b, 0);
        }

        public static Instruction sub(int a, int b) {
            return new Instruction(SUB, a, b, 0);
        }

        public static Instruction mul(int a, int b) {
            return new Instruction(MUL, a, b, 0);
        }

        public static Instruction jnz(int a, long target) {
            return new Instruction(JNZ, a, 0, target);
        }
    }

    public static Program of(Instruction... instructions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Instruction i : instructions) {
            out.write(i.op());
            out.write(i.a());
            out.write(i.b());
            for (int k = 7; k >= 0; k--) {
                out.write((int) (i.imm() >>> (8 * k)));
            }
        }
        return new Program(out.toByteArray());
    }

    /** Raw bytes, e.g. from a vector file. Any length is accepted (the VM is total); see {@link #isWellFormed()}. */
    public static Program fromBytes(byte[] code) {
        return new Program(code.clone());
    }

    /** {@code r1 = n + (n-1) + ... + 1}, then HALT. Reaches HALT after {@code 3 + 3n} steps. */
    public static Program sumLoop(long n) {
        return of(
                Instruction.mov(0, n),
                Instruction.mov(1, 0),
                Instruction.mov(2, 1),
                Instruction.add(1, 0),
                Instruction.sub(0, 2),
                Instruction.jnz(0, 3),
                Instruction.halt());
    }

    /** {@code r1 = n!} (mod 2^64), then HALT. */
    public static Program factorial(long n) {
        return of(
                Instruction.mov(0, n),
                Instruction.mov(1, 1),
                Instruction.mov(2, 1),
                Instruction.mul(1, 0),
                Instruction.sub(0, 2),
                Instruction.jnz(0, 3),
                Instruction.halt());
    }

    public byte[] code() {
        return code.clone();
    }

    byte[] raw() {
        return code;
    }

    /** Number of WHOLE instructions (a trailing partial instruction does not exist for the VM). */
    public int instructionCount() {
        return code.length / INSTRUCTION_SIZE;
    }

    /** Same rule as {@code ToyVM.isWellFormed}: 1..64 whole instructions and nothing else. */
    public boolean isWellFormed() {
        return code.length != 0
                && code.length % INSTRUCTION_SIZE == 0
                && code.length <= INSTRUCTION_SIZE * MAX_INSTRUCTIONS;
    }

    /** The on-chain commitment: {@code keccak256(code)}. */
    public Bytes32 hash() {
        return Bytes32.of(Keccak256.hash(code));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Program p && Arrays.equals(code, p.code);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(code);
    }
}
