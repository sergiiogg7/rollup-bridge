package dev.rollupbridge.core.state;

/**
 * Lifecycle of a withdrawal inside the withdrawal relayer.
 *
 * <pre>
 *   Initiated --> Included(out) --> Proven(out) --> Finalized(paid | credited)
 *                      |               |
 *                      +--> Invalidated(out) <--+   (the output was deleted)  --> Included(new out)
 * </pre>
 * "Finalizable" is not stored: it is a pure function of the chain (window elapsed, nothing challenged).
 */
public sealed interface WithdrawalState {

    /** Seen on L2, not covered by any live output yet. */
    record Initiated() implements WithdrawalState {}

    /** Covered by live output {@code outputIndex}, whose root matches the relayer own tree. */
    record Included(long outputIndex) implements WithdrawalState {}

    /** {@code proveWithdrawal} mined against {@code outputIndex}. */
    record Proven(long outputIndex, String proveTx) implements WithdrawalState {}

    /** Paid ({@code paid}) or credited to the pull-payment balance of the target (!paid). Terminal. */
    record Finalized(String txHash, boolean paid) implements WithdrawalState {}

    /** The output this withdrawal relied on was deleted; waiting for a new covering output. */
    record Invalidated(long deletedOutputIndex) implements WithdrawalState {}

    default String name() {
        return getClass().getSimpleName();
    }
}
