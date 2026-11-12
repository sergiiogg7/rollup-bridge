package dev.rollupbridge.core.state;

import java.util.Map;
import java.util.Set;

/** The allowed transitions of {@link DepositState}. The table is the single source of truth (and is tested). */
public final class DepositTransitions {
    private static final Map<Class<? extends DepositState>, Set<Class<? extends DepositState>>> ALLOWED = Map.of(
            DepositState.Seen.class,
                    Set.of(DepositState.Seen.class, DepositState.Confirmed.class, DepositState.Dropped.class),
            DepositState.Confirmed.class,
                    Set.of(DepositState.Relaying.class, DepositState.Dropped.class, DepositState.Done.class),
            DepositState.Relaying.class, Set.of(DepositState.Done.class, DepositState.Confirmed.class),
            DepositState.Done.class, Set.of(),
            DepositState.Dropped.class, Set.of(DepositState.Seen.class));

    private DepositTransitions() {}

    public static boolean allowed(DepositState from, DepositState to) {
        return ALLOWED.get(from.getClass()).contains(to.getClass());
    }

    public static void require(DepositState from, DepositState to) {
        if (!allowed(from, to)) {
            throw new IllegalStateException("illegal deposit transition " + from.name() + " -> " + to.name());
        }
    }

    public static Map<Class<? extends DepositState>, Set<Class<? extends DepositState>>> table() {
        return ALLOWED;
    }
}
