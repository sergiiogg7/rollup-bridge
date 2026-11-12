package dev.rollupbridge.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.adapters.rpc.JsonRpcClient;
import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.Program.Instruction;
import dev.rollupbridge.dispute.vm.ToyVm;
import dev.rollupbridge.dispute.vm.VmState;
import dev.rollupbridge.testkit.Anvil;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Differential fuzz of the Java VM against the REAL Solidity {@code ToyVM}, executed on a live Anvil node through
 * {@code eth_call} of the {@code VmHarness} test contract (which only exposes the library as external functions).
 * The committed vectors ({@code ToyVmTest}) pin 424 cases; this test draws thousands of fresh ones on every run.
 */
class DifferentialVmLiveTest {
    private static JsonRpcClient rpc;
    private static String harness;

    @BeforeAll
    static void deployHarness() {
        Anvil anvil = Live.anvil();
        rpc = anvil.rpc();
        harness = Live.deploy(anvil, "VmHarness.sol", "VmHarness", new byte[0]);
    }

    @Test
    @DisplayName("3000 random (program, state) pairs, plus every opcode x register edge: Solidity step == Java step")
    void solidityStepEqualsJavaStep() {
        Random rnd = new Random(Long.getLong("diff.seed", 20261112L));
        int checked = 0;
        for (int i = 0; i < 3000; i++) {
            Program program = randomProgram(rnd);
            VmState pre = randomState(rnd, program);
            assertSame(program, pre, i % 10 == 0);
            checked++;
        }
        // exhaustive over (op 0..8) x (a 0..5) x (b 0..5) at a state where every register is non-zero and distinct
        VmState pre = VmState.of(0, 7, 11, 13, MAX_U64);
        for (int op = 0; op <= 8; op++) {
            for (int a = 0; a <= 5; a++) {
                for (int b = 0; b <= 5; b++) {
                    assertSame(Program.of(new Instruction(op, a, b, 2)), pre, false);
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(3000 + 9 * 6 * 6);
    }

    private static final long MAX_U64 = -1L;

    private static void assertSame(Program program, VmState pre, boolean alsoHashes) {
        VmState javaPost = ToyVm.step(program, pre);
        VmState solidityPost = solidityStep(program, pre);
        assertThat(solidityPost).as("program %s from %s", Hex.encode(program.code()), pre).isEqualTo(javaPost);
        if (alsoHashes) {
            assertThat(solidityHash(pre)).as("hash of %s", pre).isEqualTo(pre.hash().hex());
            assertThat(solidityHash(javaPost)).isEqualTo(javaPost.hash().hex());
        }
    }

    @Test
    void wellFormednessAgrees() {
        for (int len : new int[] {0, 1, 10, 11, 12, 22, 11 * 64, 11 * 64 + 1, 11 * 65, 11 * 100}) {
            Program p = Program.fromBytes(new byte[len]);
            String data = Hex.encode(abiBytesCall("isWellFormed(bytes)", p.code()));
            var tx = JsonRpcClient.object();
            tx.put("to", harness);
            tx.put("data", data);
            boolean solidity = Abi.Words.of(rpc.call("eth_call", tx, "latest").asText()).bool(0);
            assertThat(solidity).as("length %d", len).isEqualTo(p.isWellFormed());
        }
    }

    // ----- generators -----

    private static Program randomProgram(Random rnd) {
        int len = 1 + rnd.nextInt(rnd.nextInt(10) == 0 ? 64 : 8);
        Instruction[] ins = new Instruction[len];
        for (int i = 0; i < len; i++) {
            int op = switch (rnd.nextInt(16)) {
                case 0 -> 0;
                case 1 -> 6 + rnd.nextInt(250); // invalid opcodes, including 255
                default -> 1 + rnd.nextInt(5);
            };
            int a = rnd.nextInt(8) == 0 ? 4 + rnd.nextInt(252) : rnd.nextInt(4);
            int b = rnd.nextInt(8) == 0 ? 4 + rnd.nextInt(252) : rnd.nextInt(4);
            long imm = op == Program.JNZ ? rnd.nextInt(len + 2) : word(rnd);
            ins[i] = new Instruction(op, a, b, imm);
        }
        return Program.of(ins);
    }

    private static VmState randomState(Random rnd, Program program) {
        int len = program.instructionCount();
        long pc = switch (rnd.nextInt(16)) {
            case 0 -> rnd.nextLong(); // anywhere in the 2^64 range
            case 1 -> len + rnd.nextInt(2);
            default -> rnd.nextInt(len);
        };
        return VmState.of(pc, word(rnd), word(rnd), word(rnd), word(rnd));
    }

    /** Biased 64-bit word: small numbers, the extremes and uniform values. */
    private static long word(Random rnd) {
        return switch (rnd.nextInt(8)) {
            case 0 -> 0;
            case 1 -> MAX_U64;
            case 2 -> 1L << 63;
            case 3, 4 -> rnd.nextInt(16);
            default -> rnd.nextLong();
        };
    }

    // ----- eth_call plumbing -----

    private static VmState solidityStep(Program program, VmState pre) {
        var tx = JsonRpcClient.object();
        tx.put("to", harness);
        tx.put("data", Hex.encode(encodeBytesAndState("step(bytes,(uint64,uint64[4]))", program.code(), pre)));
        Abi.Words w = Abi.Words.of(rpc.call("eth_call", tx, "latest").asText());
        return VmState.of(w.uint(0).longValue(), w.uint(1).longValue(), w.uint(2).longValue(), w.uint(3).longValue(), w.uint(4).longValue());
    }

    private static String solidityHash(VmState s) {
        var tx = JsonRpcClient.object();
        tx.put("to", harness);
        tx.put("data", Hex.encode(encodeState("hashState((uint64,uint64[4]))", s)));
        return rpc.call("eth_call", tx, "latest").asText();
    }

    private static byte[] encodeState(String signature, VmState s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Abi.selector(signature));
        for (long v : new long[] {s.pc(), s.r0(), s.r1(), s.r2(), s.r3()}) {
            out.writeBytes(word32(v));
        }
        return out.toByteArray();
    }

    /** {@code f(bytes, struct)}: head = offset(6 words in) + 5 inline struct words; tail = length + padded bytes. */
    private static byte[] encodeBytesAndState(String signature, byte[] bytes, VmState s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Abi.selector(signature));
        out.writeBytes(word32(6 * 32L));
        for (long v : new long[] {s.pc(), s.r0(), s.r1(), s.r2(), s.r3()}) {
            out.writeBytes(word32(v));
        }
        out.writeBytes(word32(bytes.length));
        out.writeBytes(bytes);
        out.writeBytes(new byte[(32 - bytes.length % 32) % 32]);
        return out.toByteArray();
    }

    private static byte[] abiBytesCall(String signature, byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Abi.selector(signature));
        out.writeBytes(word32(32));
        out.writeBytes(word32(bytes.length));
        out.writeBytes(bytes);
        out.writeBytes(new byte[(32 - bytes.length % 32) % 32]);
        return out.toByteArray();
    }

    /** A uint64 (unsigned, held in a long) as a 32-byte big-endian word. */
    private static byte[] word32(long unsigned) {
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[31 - i] = (byte) (unsigned >>> (8 * i));
        }
        return out;
    }
}
