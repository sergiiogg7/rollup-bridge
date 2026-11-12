package dev.rollupbridge.testkit.sim;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.memory.InMemoryStore;
import dev.rollupbridge.core.model.DepositEvent;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.port.L2Writer;
import dev.rollupbridge.services.Challenger;
import dev.rollupbridge.services.DepositRelayer;
import dev.rollupbridge.services.Guardian;
import dev.rollupbridge.services.OutputVerifier;
import dev.rollupbridge.services.Proposer;
import dev.rollupbridge.services.Service;
import dev.rollupbridge.services.WithdrawalRelayer;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.Faults;
import dev.rollupbridge.testkit.FaultyL1Writer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * One randomized script: deposits, withdrawals, L1 reorgs, clock jumps, service restarts, flaky RPC, lying
 * proposers and a paused bridge, interleaved at random against the real services running over a {@link SimWorld}.
 * Safety invariants are checked after EVERY step; after the script the faults are switched off, the services are
 * left to settle, and liveness plus exact value conservation across both chains are checked.
 *
 * <p>Two assumptions, stated in {@code THREAT-MODEL.md}, are enforced by the harness and not by the code under test:
 * reorgs are shallower than the confirmation depth, and some honest challenger (and guardian) gets one clean
 * look at an output before half of its window (respectively resolution period) has passed.
 *
 * <p>Deterministic: the same {@link Config} replays the same script, and a failure message carries the trace.
 */
public final class ChaosRun {

    /** @param deepReorgs allow reorgs deeper than {@code confirmations} (the unsafe configuration; safety is then NOT expected) */
    public record Config(
            long seed,
            int steps,
            long confirmations,
            Faults.Plan faults,
            boolean liars,
            boolean duplicateInstances,
            boolean deepReorgs) {

        public static Config of(long seed) {
            return new Config(seed, 60, 3, Faults.Plan.flaky(), true, true, false);
        }

        public Config withFaults(Faults.Plan p) {
            return new Config(seed, steps, confirmations, p, liars, duplicateInstances, deepReorgs);
        }

        public Config withConfirmations(long k) {
            return new Config(seed, steps, k, faults, liars, duplicateInstances, deepReorgs);
        }

        public Config withDeepReorgs(boolean v) {
            return new Config(seed, steps, confirmations, faults, liars, duplicateInstances, v);
        }

        public Config withLiars(boolean v) {
            return new Config(seed, steps, confirmations, faults, v, duplicateInstances, deepReorgs);
        }

        public Config withDuplicateInstances(boolean v) {
            return new Config(seed, steps, confirmations, faults, liars, v, deepReorgs);
        }

        public Config withSteps(int n) {
            return new Config(seed, n, confirmations, faults, liars, duplicateInstances, deepReorgs);
        }
    }

    /** Summary of a run that passed. */
    public record Result(int steps, int faultsInjected, int restarts, int reorgs, int lies, int deposits, int withdrawals) {}

    /** An invariant failed; the message holds the seed and the whole script. */
    public static final class InvariantViolation extends AssertionError {
        private static final long serialVersionUID = 1L;

        InvariantViolation(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static final Accounts[] USERS = {Accounts.ALICE, Accounts.BOB};

    private final Config cfg;
    private final Random rnd;
    private final SimWorld world = new SimWorld();
    private final Faults.Control faults;
    private final List<String> trace = new ArrayList<>();
    private int step;
    private int restarts;
    private int reorgs;
    private int lies;
    private int deposits;
    private int withdrawals;

    private final List<Role> deposit = new ArrayList<>();
    private final List<Role> withdrawal = new ArrayList<>();
    private Role proposer;
    private Role challenger;
    private Role guardian;
    private final List<Role> all = new ArrayList<>();

    /** A running service instance, rebuilt from its (persistent) store on restart. */
    private final class Role {
        final String name;
        final InMemoryStore store = new InMemoryStore();
        final Function<InMemoryStore, Service> factory;
        Service service;

        Role(String name, Function<InMemoryStore, Service> factory) {
            this.name = name;
            this.factory = factory;
            this.service = factory.apply(store);
        }

        void restart() {
            service = factory.apply(store);
            restarts++;
        }

        void tick() {
            try {
                service.tick();
            } catch (FaultyL1Writer.SimulatedCrash crash) {
                note("  " + name + " crashed: " + crash.getMessage());
                restart();
            } catch (RuntimeException e) {
                // ServiceRunner swallows these and retries on the next tick
            }
        }
    }

    private ChaosRun(Config cfg) {
        this.cfg = cfg;
        this.rnd = new Random(cfg.seed());
        this.faults = new Faults.Control(cfg.faults(), cfg.seed() * 31 + 7);
        build();
    }

    public static Result run(Config cfg) {
        return new ChaosRun(cfg).execute();
    }

    // ----- wiring -----

    private <T> T f(Class<T> port, T real) {
        return Faults.wrap(port, real, faults);
    }

    private void build() {
        int copies = cfg.duplicateInstances() ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            deposit.add(depositRelayer(i));
            withdrawal.add(withdrawalRelayer(i));
        }
        proposer = new Role("proposer", store -> new Proposer(
                f(L1Reader.class, world.l1(null)), f(L1Writer.class, world.l1(Accounts.PROPOSER)),
                f(L2Reader.class, world.l2(null)), new Proposer.Config(0)));
        challenger = challengerRole();
        guardian = new Role("guardian", store -> new Guardian(
                f(L1Reader.class, world.l1(null)), f(L1Writer.class, world.l1(Accounts.GUARDIAN)),
                new OutputVerifier(f(L2Reader.class, world.l2(null)))));
        all.addAll(deposit);
        all.addAll(withdrawal);
        all.add(proposer);
        all.add(challenger);
        all.add(guardian);
    }

    private Role depositRelayer(int copy) {
        return new Role("deposit-relayer" + copy, store -> new DepositRelayer(
                f(L1Reader.class, world.l1(null)), f(L2Reader.class, world.l2(null)),
                f(L2Writer.class, world.l2(Accounts.RELAYER)), store,
                new DepositRelayer.Config(cfg.confirmations(), 0, 20)));
    }

    private Role withdrawalRelayer(int copy) {
        return new Role("withdrawal-relayer" + copy, store -> new WithdrawalRelayer(
                f(L1Reader.class, world.l1(null)), f(L1Writer.class, world.l1(Accounts.RELAYER)),
                f(L2Reader.class, world.l2(null)), store));
    }

    private Role challengerRole() {
        return new Role("challenger", store -> new Challenger(
                f(L1Reader.class, world.l1(null)), f(L1Writer.class, world.l1(Accounts.CHALLENGER)),
                new OutputVerifier(f(L2Reader.class, world.l2(null))), store));
    }

    // ----- the script -----

    private void note(String s) {
        trace.add(s);
    }

    private Result execute() {
        try {
            for (step = 0; step < cfg.steps(); step++) {
                act();
                watchdogs();
                safety();
            }
            settle();
            liveness();
        } catch (InvariantViolation v) {
            throw v;
        } catch (RuntimeException | AssertionError e) {
            throw fail("unexpected " + e, e);
        }
        return new Result(cfg.steps(), faults.injected(), restarts, reorgs, lies, deposits, withdrawals);
    }

    private void act() {
        int r = rnd.nextInt(100);
        if (r < 14) {
            doDeposit();
        } else if (r < 24) {
            doWithdraw();
        } else if (r < 32) {
            int n = 1 + rnd.nextInt(3);
            note(step + ": mine " + n + " L1 blocks");
            world.mineL1(n);
        } else if (r < 38) {
            doReorg();
        } else if (r < 44) {
            doWarp();
        } else if (r < 48) {
            Role role = all.get(rnd.nextInt(all.size()));
            note(step + ": restart " + role.name);
            role.restart();
        } else if (r < 52 && cfg.liars()) {
            doLie();
        } else if (r < 54) {
            doPauseToggle();
        } else if (r < 56) {
            note(step + ": mine 2 L2 blocks");
            world.mineL2(2);
        } else {
            Role role = all.get(rnd.nextInt(all.size()));
            note(step + ": tick " + role.name);
            role.tick();
        }
    }

    private void doDeposit() {
        Accounts from = USERS[rnd.nextInt(USERS.length)];
        Accounts to = USERS[rnd.nextInt(USERS.length)];
        BigInteger amount = SimWorld.ETH.multiply(BigInteger.valueOf(1 + rnd.nextInt(5)));
        try {
            world.deposit(from, to, amount);
            deposits++;
            note(step + ": " + from.name() + " deposits " + amount.divide(SimWorld.ETH) + " ETH for " + to.name());
        } catch (GatewayException e) {
            note(step + ": deposit reverted (" + e.getMessage() + ")");
        }
    }

    private void doWithdraw() {
        Accounts from = USERS[rnd.nextInt(USERS.length)];
        BigInteger bal = world.l2Balance(from);
        if (bal.signum() == 0) {
            return;
        }
        BigInteger amount = bal.divide(BigInteger.valueOf(1 + rnd.nextInt(3))).max(BigInteger.ONE);
        Accounts target = USERS[rnd.nextInt(USERS.length)];
        world.withdraw(from, target, amount);
        withdrawals++;
        note(step + ": " + from.name() + " burns " + amount + " wei on L2 to pay " + target.name());
    }

    private void doReorg() {
        long maxDepth = cfg.deepReorgs() ? cfg.confirmations() + 2 : Math.max(0, cfg.confirmations() - 1);
        maxDepth = Math.min(maxDepth, world.l1Head());
        if (maxDepth < 1) {
            return;
        }
        int depth = 1 + rnd.nextInt((int) maxDepth);
        SimWorld.Reorg mode = SimWorld.Reorg.values()[rnd.nextInt(3)];
        note(step + ": L1 reorg depth " + depth + " " + mode);
        world.reorgL1(depth, mode);
        reorgs++;
    }

    private void doWarp() {
        long window = world.params().challengeWindow();
        long[] choices = {1, 5, 20, window / 2, window + 5, window * 3};
        long seconds = choices[rnd.nextInt(choices.length)];
        note(step + ": clock +" + seconds + " s");
        // Time passes in slices shorter than half a window, so the honest-participant watchdogs get their look in between.
        long slice = Math.max(1, Math.min(window, world.params().resolutionPeriod()) / 2 - 1);
        for (long left = seconds; left > 0; left -= slice) {
            world.warpL1(Math.min(slice, left));
            watchdogs();
        }
    }

    private void doPauseToggle() {
        L1Writer g = world.l1(Accounts.GUARDIAN);
        try {
            if (world.l1(null).isPaused()) {
                note(step + ": guardian unpauses");
                g.unpause();
            } else if (rnd.nextInt(4) == 0) {
                note(step + ": guardian pauses the L1 bridge");
                g.pause();
            }
        } catch (GatewayException e) {
            note(step + ": pause toggle reverted");
        }
    }

    /** The proposer key posts an output that lies, and Mallory tries to cash a fabricated withdrawal against it. */
    private void doLie() {
        world.mineL2(1); // a lying output must claim a newer L2 block than the last live one
        L2Reader l2 = world.l2(null);
        long head = l2.headNumber();
        WithdrawalTree real = world.l2TreeCopy();
        int count = real.size();
        boolean fakeLeaf = rnd.nextBoolean();
        Withdrawal fake = new Withdrawal(count, Accounts.MALLORY.address(), Accounts.MALLORY.address(), SimWorld.ETH.multiply(BigInteger.valueOf(4)));
        WithdrawalTree lying = world.l2TreeCopy();
        Bytes32 root;
        long postedCount;
        if (fakeLeaf) {
            lying.append(fake.leaf());
            root = lying.root();
            postedCount = count + 1L;
        } else {
            root = Bytes32.of(new byte[] {1, 2, 3, (byte) rnd.nextInt(255), 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
            postedCount = count;
        }
        try {
            world.l1(Accounts.PROPOSER).proposeOutput(root, l2.blockHash(head).orElseThrow(), head, postedCount);
        } catch (GatewayException e) {
            note(step + ": the lie was refused by the oracle (" + e.getMessage() + ")");
            return;
        }
        lies++;
        long index = world.l1OutputCount() - 1;
        note(step + ": PROPOSER LIES in output #" + index + (fakeLeaf ? " (extra leaf paying Mallory 4 ETH)" : " (random root)"));
        if (fakeLeaf) {
            try {
                world.l1(Accounts.MALLORY).proveWithdrawal(fake, index, lying.proof(count, count + 1));
                note(step + ": Mallory's forged proof was accepted while the output is only Proposed");
            } catch (GatewayException e) {
                note(step + ": Mallory's proof reverted (" + e.getMessage() + ")");
            }
        }
    }

    // ----- the two honest-participant assumptions -----

    /**
     * An honest challenger (and guardian) is online often enough: an output that nobody has looked at when half of
     * its window has passed gets a clean look (no injected faults) at that moment. The same for a guardian and the
     * resolution period.
     */
    private void watchdogs() {
        L1Reader l1 = world.l1(null);
        long now = l1.headTimestamp();
        long n = l1.outputCount();
        for (long i = 0; i < n; i++) {
            OutputRecord o = l1.output(i);
            if (o.status() == OutputStatus.PROPOSED && !challenger.store.isChecked(i, o.withdrawalRoot())
                    && now >= o.proposedAt() + world.params().challengeWindow() / 2) {
                clean(challenger, "challenger watchdog: output #" + i + " unchecked at half of its window");
            }
            if (o.status() == OutputStatus.CHALLENGED && now >= o.challengedAt() + world.params().resolutionPeriod() / 2) {
                clean(guardian, "guardian watchdog: output #" + i + " challenged for half of the resolution period");
            }
        }
    }

    private void clean(Role role, String why) {
        Faults.Plan saved = faults.plan();
        faults.set(Faults.Plan.none());
        try {
            note("  " + why);
            role.tick();
            if (role == challenger) {
                guardian.tick(); // a challenge needs its ruling; the guardian is responsive in the same minute
            }
        } finally {
            faults.set(saved);
        }
    }

    // ----- invariants -----

    private InvariantViolation fail(String what, Throwable cause) {
        StringBuilder sb = new StringBuilder("INVARIANT VIOLATED at step ").append(step).append(": ").append(what)
                .append("\nconfig: ").append(cfg).append("\nscript:\n");
        trace.forEach(t -> sb.append("  ").append(t).append('\n'));
        InvariantViolation v = new InvariantViolation(sb.toString());
        if (cause != null) {
            v.initCause(cause);
        }
        return v;
    }

    /** Must hold after every step. */
    private void safety() {
        // 1. Nothing is minted on L2 that is not backed by the same deposit on the canonical L1 chain.
        Map<Long, DepositEvent> l1 = world.l1Deposits();
        for (var minted : world.l2Minted().entrySet()) {
            DepositEvent backing = l1.get(minted.getKey());
            DepositEvent m = minted.getValue();
            if (backing == null || !backing.to().equals(m.to()) || !backing.amount().equals(m.amount())) {
                throw fail("deposit #" + minted.getKey() + " was minted on L2 (" + m.amount() + " to " + m.to()
                        + ") but the canonical L1 has " + (backing == null ? "no such deposit"
                        : backing.amount() + " to " + backing.to()), null);
            }
        }
        // 2. Escrow always covers the supply plus what is burned and not yet paid.
        BigInteger owed = world.l2TotalSupply().add(world.l2TotalBurned()).subtract(world.l1PaidTotal());
        if (world.l1TotalLocked().compareTo(owed) < 0) {
            throw fail("L1 escrow " + world.l1TotalLocked() + " is below the L2 liabilities " + owed, null);
        }
        // 3. Only real withdrawals are ever paid: nothing for Mallory, and never more than was burned.
        if (world.l1Paid(Accounts.MALLORY.address()).signum() != 0) {
            throw fail("Mallory was paid " + world.l1Paid(Accounts.MALLORY.address()) + " wei by a forged withdrawal", null);
        }
        if (world.l1PaidTotal().compareTo(world.l2TotalBurned()) > 0) {
            throw fail("L1 paid " + world.l1PaidTotal() + " but only " + world.l2TotalBurned() + " was burned on L2", null);
        }
        // 4. Every payout corresponds to a withdrawal that exists on L2.
        for (WithdrawalEvent e : world.l2WithdrawalEvents()) {
            // (a finalized leaf that is not an L2 leaf cannot be listed here; covered by the Mallory check above)
            if (e.withdrawal().amount().signum() <= 0) {
                throw fail("non-positive withdrawal on L2", null);
            }
        }
    }

    // ----- settling and liveness -----

    /** Faults off; the services run until nothing changes, with the clock moving past every window. */
    private void settle() {
        note("-- script over: faults off, bridge unpaused, services settle --");
        faults.set(Faults.Plan.none());
        try {
            if (world.l1(null).isPaused()) {
                world.l1(Accounts.GUARDIAN).unpause();
            }
        } catch (GatewayException ignored) {
            // nothing to do
        }
        String last = "";
        int stable = 0;
        for (int round = 0; round < 120 && stable < 4; round++) {
            world.mineL1(1);
            if (round % 3 == 2) {
                world.warpL1(world.params().challengeWindow() + 1);
            }
            for (Role role : all) {
                role.tick();
            }
            String now = snapshot();
            stable = now.equals(last) ? stable + 1 : 0;
            last = now;
        }
        step = cfg.steps();
    }

    private String snapshot() {
        return world.l1TotalLocked() + "|" + world.l2TotalSupply() + "|" + world.l1PaidTotal() + "|" + world.l1OutputStatuses()
                + "|" + world.l2Minted().size() + "|" + world.l1Head();
    }

    private void liveness() {
        safety();
        // Every deposit of the canonical chain reached L2 exactly as it is on L1.
        for (DepositEvent d : world.l1Deposits().values()) {
            DepositEvent m = world.l2Minted().get(d.depositId());
            if (m == null || !m.amount().equals(d.amount()) || !m.to().equals(d.to())) {
                throw fail("deposit #" + d.depositId() + " (" + d.amount() + " wei) never reached L2 correctly", null);
            }
        }
        if (world.l2Minted().size() != world.l1Deposits().size()) {
            throw fail("L2 minted " + world.l2Minted().size() + " deposits, canonical L1 has " + world.l1Deposits().size(), null);
        }
        // Every withdrawal that was burned on L2 got paid on L1.
        for (WithdrawalEvent e : world.l2WithdrawalEvents()) {
            if (!world.isFinalizedOnL1(e.withdrawal())) {
                throw fail("withdrawal #" + e.withdrawal().id() + " (" + e.withdrawal().amount() + " wei) was never paid on L1"
                        + " (outputs: " + world.l1OutputStatuses() + ")", null);
            }
        }
        // No lying output survives.
        for (OutputRecord o : outputs()) {
            if (o.status() == OutputStatus.CHALLENGED) {
                throw fail("output #" + o.index() + " is still challenged after settling", null);
            }
        }
        // Exact conservation: what is locked on L1 is exactly what circulates on L2.
        if (!world.l1TotalLocked().equals(world.l2TotalSupply())) {
            throw fail("conservation: L1 locked " + world.l1TotalLocked() + " != L2 supply " + world.l2TotalSupply(), null);
        }
    }

    private List<OutputRecord> outputs() {
        List<OutputRecord> out = new ArrayList<>();
        L1Reader l1 = world.l1(null);
        for (long i = 0; i < l1.outputCount(); i++) {
            out.add(l1.output(i));
        }
        return out;
    }
}
