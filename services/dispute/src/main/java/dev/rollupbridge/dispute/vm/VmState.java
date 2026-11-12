package dev.rollupbridge.dispute.vm;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Keccak256;

/**
 * The whole state of the toy machine: a program counter and four 64-bit registers. All five values are UNSIGNED
 * 64-bit numbers stored in Java {@code long}s (two's complement addition, subtraction and multiplication are the same
 * bit operations as modulo 2^64 arithmetic; comparisons use {@link Long#compareUnsigned}).
 */
public record VmState(long pc, long r0, long r1, long r2, long r3) {
    public static final int REGISTERS = 4;

    /** The all-zero state used by the standard programs. */
    public static VmState zero() {
        return new VmState(0, 0, 0, 0, 0);
    }

    public static VmState of(long pc, long... regs) {
        if (regs.length != REGISTERS) {
            throw new IllegalArgumentException("expected 4 registers, got " + regs.length);
        }
        return new VmState(pc, regs[0], regs[1], regs[2], regs[3]);
    }

    public long reg(int index) {
        return switch (index) {
            case 0 -> r0;
            case 1 -> r1;
            case 2 -> r2;
            case 3 -> r3;
            default -> throw new IllegalArgumentException("no register " + index);
        };
    }

    public VmState withReg(int index, long value) {
        return switch (index) {
            case 0 -> new VmState(pc, value, r1, r2, r3);
            case 1 -> new VmState(pc, r0, value, r2, r3);
            case 2 -> new VmState(pc, r0, r1, value, r3);
            case 3 -> new VmState(pc, r0, r1, r2, value);
            default -> throw new IllegalArgumentException("no register " + index);
        };
    }

    public VmState withPc(long newPc) {
        return new VmState(newPc, r0, r1, r2, r3);
    }

    /**
     * {@code keccak256(abi.encode(pc, r0, r1, r2, r3))}: five 32-byte big-endian words, exactly what the
     * Solidity {@code ToyVM.hashState} computes.
     */
    public Bytes32 hash() {
        byte[] data = new byte[5 * 32];
        putWord(data, 0, pc);
        putWord(data, 1, r0);
        putWord(data, 2, r1);
        putWord(data, 3, r2);
        putWord(data, 4, r3);
        return Bytes32.of(Keccak256.hash(data));
    }

    private static void putWord(byte[] data, int word, long value) {
        int end = (word + 1) * 32;
        for (int i = 0; i < 8; i++) {
            data[end - 1 - i] = (byte) (value >>> (8 * i));
        }
    }

    @Override
    public String toString() {
        return "pc=" + Long.toUnsignedString(pc) + " r=[" + Long.toUnsignedString(r0) + ", " + Long.toUnsignedString(r1)
                + ", " + Long.toUnsignedString(r2) + ", " + Long.toUnsignedString(r3) + "]";
    }
}
