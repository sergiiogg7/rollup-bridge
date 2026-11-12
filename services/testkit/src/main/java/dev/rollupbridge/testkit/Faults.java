package dev.rollupbridge.testkit;

import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.GatewayException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Fault injection for ANY port (L1Reader, L1Writer, L2Reader, L2Writer), over a real Anvil gateway or the
 * in-memory model: a dynamic proxy that misbehaves the way a flaky RPC node does. A {@link Control} holds the
 * current {@link Plan} and the random source, so a test can switch faults on and off while services keep running
 * and a failing seed replays exactly.
 *
 * <ul>
 *   <li><b>errors</b>: the call fails before it reaches the node (a transport error; nothing was mined);</li>
 *   <li><b>lost responses</b>: a transaction IS mined but the caller only sees a transport error (timeout after send);</li>
 *   <li><b>latency</b>: a random pause before the call;</li>
 *   <li><b>duplicated and shuffled logs</b>: event queries return some events twice and in random order;</li>
 *   <li><b>stale heads</b>: {@code headNumber()} returns a block up to N behind the real one;</li>
 *   <li><b>crash after a mined transaction</b>: {@link FaultyL1Writer.SimulatedCrash}, the {@code kill -9} of the
 *       worst moment (mined, but the service never wrote its state).</li>
 * </ul>
 */
public final class Faults {
    private Faults() {}

    /** What misbehaves and how often; probabilities are per call. */
    public record Plan(
            double errorRate,
            double lostResponseRate,
            long maxLatencyMillis,
            double duplicateLogsRate,
            double shuffleLogsRate,
            int maxStaleBlocks,
            double crashAfterTxRate) {

        public static Plan none() {
            return new Plan(0, 0, 0, 0, 0, 0, 0);
        }

        /** Everything at once, at the rates the chaos scripts use. */
        public static Plan flaky() {
            return new Plan(0.12, 0.05, 0, 0.3, 0.3, 2, 0.04);
        }

        public Plan withErrors(double rate) {
            return new Plan(rate, lostResponseRate, maxLatencyMillis, duplicateLogsRate, shuffleLogsRate, maxStaleBlocks, crashAfterTxRate);
        }

        public Plan withLostResponses(double rate) {
            return new Plan(errorRate, rate, maxLatencyMillis, duplicateLogsRate, shuffleLogsRate, maxStaleBlocks, crashAfterTxRate);
        }

        public Plan withLatency(long maxMillis) {
            return new Plan(errorRate, lostResponseRate, maxMillis, duplicateLogsRate, shuffleLogsRate, maxStaleBlocks, crashAfterTxRate);
        }

        public Plan withLogChaos(double duplicate, double shuffle) {
            return new Plan(errorRate, lostResponseRate, maxLatencyMillis, duplicate, shuffle, maxStaleBlocks, crashAfterTxRate);
        }

        public Plan withStaleHeads(int maxBlocks) {
            return new Plan(errorRate, lostResponseRate, maxLatencyMillis, duplicateLogsRate, shuffleLogsRate, maxBlocks, crashAfterTxRate);
        }

        public Plan withCrashes(double rate) {
            return new Plan(errorRate, lostResponseRate, maxLatencyMillis, duplicateLogsRate, shuffleLogsRate, maxStaleBlocks, rate);
        }
    }

    /** The current plan plus the seeded random source; shared by every decorated port of one scenario. */
    public static final class Control {
        private volatile Plan plan;
        private final Random random;
        private int injected;

        public Control(Plan plan, long seed) {
            this.plan = plan;
            this.random = new Random(seed);
        }

        public void set(Plan plan) {
            this.plan = plan;
        }

        public Plan plan() {
            return plan;
        }

        /** How many faults were injected so far (a test can assert that its plan did something). */
        public synchronized int injected() {
            return injected;
        }

        private synchronized boolean roll(double probability) {
            boolean hit = probability > 0 && random.nextDouble() < probability;
            if (hit) {
                injected++;
            }
            return hit;
        }

        private synchronized int nextInt(int bound) {
            return bound <= 0 ? 0 : random.nextInt(bound + 1);
        }

        private synchronized long nextLong(long bound) {
            return bound <= 0 ? 0 : (long) (random.nextDouble() * (bound + 1));
        }

        private synchronized void shuffle(List<?> list) {
            Collections.shuffle(list, random);
        }
    }

    private static final Set<String> LOG_QUERIES = Set.of("depositEvents", "withdrawalEvents");

    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> port, T delegate, Control control) {
        return (T) Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
            Plan plan = control.plan();
            String name = method.getName();
            if (plan.maxLatencyMillis() > 0) {
                Thread.sleep(control.nextLong(plan.maxLatencyMillis()));
            }
            if (control.roll(plan.errorRate())) {
                throw new GatewayException(name + " transport error: injected fault (nothing was sent)");
            }
            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (result instanceof TxResult) {
                if (control.roll(plan.crashAfterTxRate())) {
                    throw new FaultyL1Writer.SimulatedCrash("kill -9 right after " + name + " was mined");
                }
                if (control.roll(plan.lostResponseRate())) {
                    throw new GatewayException(name + " transport error: injected fault (the transaction WAS mined)");
                }
            }
            if (name.equals("headNumber") && result instanceof Long head && plan.maxStaleBlocks() > 0) {
                return Math.max(0L, head - control.nextInt(plan.maxStaleBlocks()));
            }
            if (LOG_QUERIES.contains(name) && result instanceof List<?> events && !events.isEmpty()) {
                List<Object> out = new ArrayList<>(events);
                if (control.roll(plan.duplicateLogsRate())) {
                    out.add(out.get(control.nextInt(out.size() - 1)));
                }
                if (control.roll(plan.shuffleLogsRate())) {
                    control.shuffle(out);
                }
                return out;
            }
            return result;
        });
    }
}
