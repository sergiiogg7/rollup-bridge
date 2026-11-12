package dev.rollupbridge.core.state;

/**
 * Lifecycle of a deposit inside the relayer. Sealed, so a {@code switch} over it is checked by the compiler:
 * adding a state forces every consumer to handle it.
 *
 * <pre>
 *   Seen --(k confirmations, hash still canonical)--> Confirmed --(intent persisted)--> Relaying --> Done
 *    |                                                    |                               |
 *    +-----------(block reorged away)--> Dropped <--------+      (send failed) Relaying --> Confirmed
 * </pre>
 */
public sealed interface DepositState {

    /** Found in an L1 block, not yet deep enough. */
    record Seen() implements DepositState {}

    /** Deep enough and the block hash was re-checked as canonical: safe to relay. */
    record Confirmed() implements DepositState {}

    /** The intent to mint on L2 is persisted; the L2 transaction may or may not have been sent. */
    record Relaying() implements DepositState {}

    /** Minted on L2 by {@code l2TxHash} (or found already processed on L2). Terminal. */
    record Done(String l2TxHash) implements DepositState {}

    /** The L1 block that contained the deposit is no longer canonical. May become {@link Seen} again. */
    record Dropped() implements DepositState {}

    default String name() {
        return getClass().getSimpleName();
    }
}
