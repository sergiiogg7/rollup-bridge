package dev.rollupbridge.dispute.vm;

/**
 * The Java reference implementation of the toy VM: the same rules as the Solidity library {@code ToyVM}, which the
 * differential tests check instruction by instruction (see docs/DISPUTE-GAME.md).
 *
 * <p>{@link #step} is TOTAL: a program counter past the end of the program, an unknown opcode and a register
 * index above 3 all behave like HALT (the state is returned unchanged).
 */
public final class ToyVm {
    private ToyVm() {}

    /** Executes exactly one instruction. */
    public static VmState step(Program program, VmState s) {
        byte[] code = program.raw();
        int count = program.instructionCount();
        if (Long.compareUnsigned(s.pc(), count) >= 0) {
            return s;
        }
        int base = (int) s.pc() * Program.INSTRUCTION_SIZE;
        int op = code[base] & 0xff;
        int a = code[base + 1] & 0xff;
        int b = code[base + 2] & 0xff;
        long imm = 0;
        for (int i = 0; i < 8; i++) {
            imm = (imm << 8) | (code[base + 3 + i] & 0xff);
        }

        if (op == Program.HALT || a >= VmState.REGISTERS) {
            return s;
        }
        switch (op) {
            case Program.MOV:
                return s.withReg(a, imm).withPc(s.pc() + 1);
            case Program.JNZ:
                return s.reg(a) != 0 ? s.withPc(imm) : s.withPc(s.pc() + 1);
            case Program.ADD, Program.SUB, Program.MUL: {
                if (b >= VmState.REGISTERS) {
                    return s;
                }
                long x = s.reg(a);
                long y = s.reg(b);
                long r = op == Program.ADD ? x + y : op == Program.SUB ? x - y : x * y;
                return s.withReg(a, r).withPc(s.pc() + 1);
            }
            default:
                return s; // unknown opcode: trap
        }
    }

    /** The state after {@code n} steps (n is an unsigned count; a halted machine stops early). */
    public static VmState run(Program program, VmState init, long n) {
        VmState s = init;
        for (long i = 0; i != n; i++) {
            VmState next = step(program, s);
            if (next.equals(s)) {
                return s; // fixed point (HALT, trap or pc past the end): every later state is the same
            }
            s = next;
        }
        return s;
    }
}
