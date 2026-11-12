package dev.rollupbridge.core.port;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.model.WithdrawalEvent;
import java.util.List;
import java.util.Optional;

/** Read side of the L2 gateway. */
public interface L2Reader {
    long headNumber();

    Optional<Bytes32> blockHash(long number);

    /** {@code WithdrawalInitiated} logs in [from, to]. */
    List<WithdrawalEvent> withdrawalEvents(long fromBlock, long toBlock);

    /** {@code L2Bridge.withdrawalRoot()} evaluated at the given historical block. */
    Bytes32 withdrawalRootAt(long block);

    /** {@code L2Bridge.withdrawalCount()} evaluated at the given historical block. */
    long withdrawalCountAt(long block);

    boolean isDepositProcessed(long depositId);
}
