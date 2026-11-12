package dev.rollupbridge.core.state;

import dev.rollupbridge.core.Bytes32;
import java.math.BigInteger;

/** A deposit and where it is in the relayer lifecycle. */
public record DepositRecord(
        long id, String to, BigInteger amount, long l1Block, Bytes32 l1BlockHash, DepositState state) {

    /** Returns a copy in {@code next}, enforcing the transition table. */
    public DepositRecord transitionTo(DepositState next) {
        DepositTransitions.require(state, next);
        return new DepositRecord(id, to, amount, l1Block, l1BlockHash, next);
    }

    /** The same deposit id seen again in a (possibly different) L1 block after a reorg: back to Seen. */
    public DepositRecord reobserved(long block, Bytes32 hash, String newTo, BigInteger newAmount) {
        DepositState next = new DepositState.Seen();
        DepositTransitions.require(state, next);
        return new DepositRecord(id, newTo, newAmount, block, hash, next);
    }
}
