package dev.rollupbridge.core.state;

import java.util.Map;
import java.util.Set;

/** The allowed transitions of {@link WithdrawalState}. */
public final class WithdrawalTransitions {
    private static final Map<Class<? extends WithdrawalState>, Set<Class<? extends WithdrawalState>>> ALLOWED = Map.of(
            WithdrawalState.Initiated.class,
                    Set.of(WithdrawalState.Included.class, WithdrawalState.Finalized.class),
            WithdrawalState.Included.class,
                    Set.of(WithdrawalState.Proven.class, WithdrawalState.Invalidated.class, WithdrawalState.Finalized.class),
            WithdrawalState.Proven.class, Set.of(WithdrawalState.Finalized.class, WithdrawalState.Invalidated.class),
            WithdrawalState.Invalidated.class, Set.of(WithdrawalState.Included.class, WithdrawalState.Finalized.class),
            // The finalize transaction itself can be removed by an L1 reorg: the relayer must notice and start over.
            WithdrawalState.Finalized.class, Set.of(WithdrawalState.Invalidated.class));

    private WithdrawalTransitions() {}

    public static boolean allowed(WithdrawalState from, WithdrawalState to) {
        return ALLOWED.get(from.getClass()).contains(to.getClass());
    }

    public static void require(WithdrawalState from, WithdrawalState to) {
        if (!allowed(from, to)) {
            throw new IllegalStateException("illegal withdrawal transition " + from.name() + " -> " + to.name());
        }
    }

    public static Map<Class<? extends WithdrawalState>, Set<Class<? extends WithdrawalState>>> table() {
        return ALLOWED;
    }
}
