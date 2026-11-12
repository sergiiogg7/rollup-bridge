package dev.rollupbridge.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.testkit.Faults;
import dev.rollupbridge.testkit.sim.ChaosRun;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 of the plan: random interleavings of deposits, withdrawals, L1 reorgs, restarts, flaky RPCs, lying
 * proposers and pauses, against the REAL services over the in-memory model of both chains ({@code SimWorld}), with the
 * safety invariants checked after every step and liveness plus exact value conservation at the end.
 *
 * <p>{@code CHAOS_SCRIPTS} (default 1000, which is the CI profile) and {@code CHAOS_SEED} (first seed, default 1) tune
 * the run. A failure prints the seed and the whole script: rerun with {@code CHAOS_SEED=<seed> CHAOS_SCRIPTS=1}.
 */
class ChaosPropertyTest {

    private static int scripts() {
        return Integer.parseInt(System.getenv().getOrDefault("CHAOS_SCRIPTS", "1000"));
    }

    private static long firstSeed() {
        return Long.parseLong(System.getenv().getOrDefault("CHAOS_SEED", "1"));
    }

    @Test
    void invariantsHoldOverRandomScriptsWithFlakyRpcsReorgsRestartsAndLyingProposers() {
        long deposits = 0, withdrawals = 0, faults = 0, reorgs = 0, restarts = 0, lies = 0;
        for (long seed = firstSeed(); seed < firstSeed() + scripts(); seed++) {
            ChaosRun.Result r = ChaosRun.run(ChaosRun.Config.of(seed));
            deposits += r.deposits();
            withdrawals += r.withdrawals();
            faults += r.faultsInjected();
            reorgs += r.reorgs();
            restarts += r.restarts();
            lies += r.lies();
        }
        System.out.printf(
                "chaos: %d scripts passed (%d deposits, %d withdrawals, %d reorgs, %d restarts incl. crashes, %d lying outputs, %d injected RPC faults)%n",
                scripts(), deposits, withdrawals, reorgs, restarts, lies, faults);
        // the scripts must actually exercise what they claim to
        assertThat(deposits).isGreaterThan(scripts());
        assertThat(withdrawals).isGreaterThan(scripts() / 4);
        assertThat(faults).isGreaterThan(scripts());
        assertThat(reorgs).isGreaterThan(scripts() / 4);
        assertThat(lies).isGreaterThan(scripts() / 10);
    }

    @Test
    void theSameInvariantsHoldWithoutFaultsAndWithASingleInstancePerRole() {
        for (long seed = firstSeed(); seed < firstSeed() + Math.max(50, scripts() / 10); seed++) {
            ChaosRun.run(ChaosRun.Config.of(seed).withFaults(Faults.Plan.none()).withDuplicateInstances(false));
        }
    }

    /** The harness has teeth: with confirmation depth 0 and reorgs the safety invariant is violated (E6 unsafe, in bulk). */
    @Test
    void theHarnessDetectsTheLossThatZeroConfirmationsAllows() {
        int violations = 0;
        for (long seed = 1; seed <= 300; seed++) {
            try {
                ChaosRun.run(ChaosRun.Config.of(seed).withConfirmations(0).withDeepReorgs(true).withFaults(Faults.Plan.none()).withLiars(false));
            } catch (ChaosRun.InvariantViolation expected) {
                assertThat(expected.getMessage()).contains("script:");
                if (expected.getMessage().contains("minted on L2")) {
                    violations++;
                }
            }
        }
        assertThat(violations).as("scripts that exposed an unbacked mint").isGreaterThan(0);
    }
}
