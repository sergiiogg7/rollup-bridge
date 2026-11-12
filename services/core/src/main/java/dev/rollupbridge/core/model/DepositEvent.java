package dev.rollupbridge.core.model;

import dev.rollupbridge.core.Bytes32;
import java.math.BigInteger;

/** {@code DepositInitiated} as seen in an L1 log, remembering the block it was mined in. */
public record DepositEvent(long depositId, String from, String to, BigInteger amount, long l1Block, Bytes32 l1BlockHash) {}
