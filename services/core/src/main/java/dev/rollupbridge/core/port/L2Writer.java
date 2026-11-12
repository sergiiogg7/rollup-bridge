package dev.rollupbridge.core.port;

import dev.rollupbridge.core.model.TxResult;
import java.math.BigInteger;

/** Write side of the L2 gateway (only the deposit relayer writes to L2 in this system). */
public interface L2Writer {
    TxResult finalizeDeposit(long depositId, String to, BigInteger amount);
}
