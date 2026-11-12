package dev.rollupbridge.core.model;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Withdrawal;

/** {@code WithdrawalInitiated} as seen in an L2 log. */
public record WithdrawalEvent(Withdrawal withdrawal, Bytes32 leaf, Bytes32 newRoot, long l2Block, Bytes32 l2BlockHash) {}
