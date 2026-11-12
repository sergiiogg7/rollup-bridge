package dev.rollupbridge.dispute.bot;

import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Keccak256;
import dev.rollupbridge.dispute.vm.Trace;
import dev.rollupbridge.dispute.vm.VmState;
import java.util.Random;

/**
 * What a proposer ASSERTS about the state at each step: one function from a step number to a state hash. The honest
 * function is the true trace; every lying strategy bends it differently. A proposer can be asked about any step in
 * {@code 1..N} during the bisection, in any order, so a strategy must answer for all of them.
 */
public final class Claims {
    private Claims() {}

    /** The claim at a step. */
    @FunctionalInterface
    public interface ClaimFunction {
        Bytes32 at(long step);
    }

    /** Always tells the truth. */
    public static ClaimFunction honest(Trace truth) {
        return truth::hashAt;
    }

    /**
     * Truthful up to step {@code from - 1}; from {@code from} on, every state hash is simply wrong (a hash of the
     * true hash with a salt). {@code from = N} is the "lie only about the final state" liar.
     */
    public static ClaimFunction wrongFrom(Trace truth, long from, long salt) {
        return step -> step < from ? truth.hashAt(step) : salted(truth.hashAt(step), salt);
    }

    /**
     * The smart liar: truthful up to step {@code k - 1}; the state at step {@code k} is CORRUPTED (one register is
     * off by one) and from there on the claims follow a perfectly valid execution of the program from the corrupted
     * state. Only one transition (k-1 to k) is invalid, and only the one-step proof can prove it.
     */
    public static ClaimFunction consistentLieFrom(Trace truth, long k) {
        VmState real = truth.at(k);
        VmState corrupted = real.withReg(1, real.r1() + 1);
        Trace forked = new Trace(truth.program(), corrupted);
        return step -> step < k ? truth.hashAt(step) : forked.hashAt(step - k);
    }

    /** Garbage at every step (a different random hash per step number), the "I do not even try" liar. */
    public static ClaimFunction garbage(long seed) {
        return step -> {
            byte[] material = Abi.encode(java.util.List.of(Abi.uint(seed & Long.MAX_VALUE), Abi.uint(step)));
            return Bytes32.of(Keccak256.hash(material));
        };
    }

    /**
     * Answers truthfully for steps in the lower half of the claimed run and lies for the upper half: the liar tries
     * to hide behind an honest-looking prefix. The lie starts at a random step.
     */
    public static ClaimFunction randomLie(Trace truth, long steps, Random rnd) {
        long from = 1 + (long) (rnd.nextDouble() * steps);
        return rnd.nextBoolean() ? wrongFrom(truth, Math.min(from, steps), rnd.nextLong()) : consistentLieFrom(truth, Math.min(from, steps));
    }

    private static Bytes32 salted(Bytes32 h, long salt) {
        byte[] material = Abi.encode(java.util.List.of(Abi.bytes32(h), Abi.uint(Math.abs(salt % 1_000_000_007L))));
        return Bytes32.of(Keccak256.hash(material));
    }
}
