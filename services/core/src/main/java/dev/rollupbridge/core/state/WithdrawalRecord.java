package dev.rollupbridge.core.state;

import dev.rollupbridge.core.Withdrawal;

/** A withdrawal and where it is in the relayer lifecycle. */
public record WithdrawalRecord(Withdrawal withdrawal, long l2Block, WithdrawalState state) {

    public WithdrawalRecord transitionTo(WithdrawalState next) {
        WithdrawalTransitions.require(state, next);
        return new WithdrawalRecord(withdrawal, l2Block, next);
    }
}
