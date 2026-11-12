package dev.rollupbridge.services;

import dev.rollupbridge.core.metrics.MetricsRegistry;
import dev.rollupbridge.core.model.TxResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Decorates a port (L1Reader, L1Writer, L2Reader, L2Writer, ...) so that every failure is counted in
 * {@code bridge_service_errors_total{service,reason}} even when the service swallows the exception and retries
 * on the next tick, and every transaction is counted in {@code bridge_transactions_total{service,op,outcome}}.
 * Services stay unaware of metrics (a dynamic proxy, the same idea as a Spring AOP aspect).
 *
 * <p>Domain counters of the plan ({@code bridge_outputs_proposed_total}, {@code bridge_outputs_challenged_total})
 * are incremented here too, when the matching transaction was mined successfully.
 */
public final class Metered {
    /** Exceptions already counted by a decorator, so that {@link ServiceRunner} does not count them a second time. */
    private static final Set<Throwable> COUNTED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private Metered() {}

    /** True if a metered port already counted this exception (it then escaped the tick). */
    static boolean alreadyCounted(Throwable t) {
        return COUNTED.contains(t);
    }

    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> port, T delegate, MetricsRegistry metrics, String service) {
        var errors = metrics.counter("bridge_service_errors_total", "Failures by service and reason (swallowed or not).");
        var txs = metrics.counter("bridge_transactions_total", "Transactions sent by service and operation.");
        var proposed = metrics.counter("bridge_outputs_proposed_total", "Output roots posted to L1.");
        var challenged = metrics.counter("bridge_outputs_challenged_total", "Outputs challenged on L1.");
        var resolved = metrics.counter("bridge_challenges_resolved_total", "Challenges ruled on by the guardian.");
        return (T) Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
            boolean isTx = method.getReturnType() == TxResult.class;
            try {
                Object result = method.invoke(delegate, args);
                if (isTx) {
                    boolean ok = ((TxResult) result).success();
                    txs.inc("service", service, "op", method.getName(), "outcome", ok ? "ok" : "failed");
                    if (ok) {
                        switch (method.getName()) {
                            case "proposeOutput" -> proposed.inc();
                            case "challengeOutput" -> challenged.inc();
                            case "resolveChallenge" -> resolved.inc("proposer_was_wrong", String.valueOf(args[1]));
                            default -> { }
                        }
                    }
                }
                return result;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (isTx) {
                    txs.inc("service", service, "op", method.getName(), "outcome", "error");
                }
                errors.inc("service", service, "reason", ErrorReasons.of(cause));
                COUNTED.add(cause);
                throw cause;
            }
        });
    }
}
