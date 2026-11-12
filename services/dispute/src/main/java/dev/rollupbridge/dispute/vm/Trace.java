package dev.rollupbridge.dispute.vm;

import dev.rollupbridge.core.Bytes32;
import java.util.ArrayList;
import java.util.List;

/**
 * The TRUE execution of a program from an initial state: random access to the state (and its hash) after any number
 * of steps. A bot needs this to know what is honest at the midpoints it is asked about. It keeps a checkpoint every
 * {@value #CHECKPOINT_EVERY} steps and re-executes at most that many steps per query, so a computation of 2^28 steps
 * needs a few tens of thousands of states in memory, not 2^28. A halted (or looping-in-place) machine is detected as a
 * fixed point and answered in O(1) for every later step, which makes absurdly long claims (2^50 steps) cheap to play.
 */
public final class Trace {
    static final int CHECKPOINT_EVERY = 4096;

    private final Program program;
    private final List<VmState> checkpoints = new ArrayList<>(); // checkpoints.get(k) = state after k * EVERY steps
    private VmState frontier; // furthest state computed so far
    private long frontierStep;
    private long fixedFrom = -1; // first step from which the state never changes again, or -1 if unknown yet
    private VmState fixedState;

    public Trace(Program program, VmState init) {
        this.program = program;
        this.checkpoints.add(init);
        this.frontier = init;
        this.frontierStep = 0;
    }

    public Program program() {
        return program;
    }

    public VmState initial() {
        return checkpoints.get(0);
    }

    /** The state after {@code n} steps ({@code n >= 0}). */
    public synchronized VmState at(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative step " + n);
        }
        if (fixedFrom >= 0 && n >= fixedFrom) {
            return fixedState;
        }
        long wantedCheckpoint = n / CHECKPOINT_EVERY;
        extendTo(Math.min(n, wantedCheckpoint * CHECKPOINT_EVERY));
        if (fixedFrom >= 0 && n >= fixedFrom) {
            return fixedState;
        }
        VmState s = checkpoints.get((int) wantedCheckpoint);
        for (long step = wantedCheckpoint * CHECKPOINT_EVERY; step < n; step++) {
            VmState next = ToyVm.step(program, s);
            if (next.equals(s)) {
                return s;
            }
            s = next;
        }
        return s;
    }

    public Bytes32 hashAt(long n) {
        return at(n).hash();
    }

    /** Advances the frontier to step {@code target} recording checkpoints; stops early at a fixed point. */
    private void extendTo(long target) {
        while (frontierStep < target && fixedFrom < 0) {
            VmState next = ToyVm.step(program, frontier);
            if (next.equals(frontier)) {
                fixedFrom = frontierStep;
                fixedState = frontier;
                return;
            }
            frontier = next;
            frontierStep++;
            if (frontierStep % CHECKPOINT_EVERY == 0) {
                checkpoints.add(frontier);
            }
        }
    }
}
