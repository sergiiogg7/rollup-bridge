package dev.rollupbridge.core.port;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.model.TxResult;
import java.util.List;

/** Write side of the L1 gateway. One instance is bound to one signing key (one role). */
public interface L1Writer {
    TxResult proposeOutput(Bytes32 root, Bytes32 l2BlockHash, long l2BlockNumber, long withdrawalCount);

    TxResult challengeOutput(long index);

    TxResult resolveChallenge(long index, boolean proposerWasWrong);

    TxResult expireChallenge(long index);

    TxResult deleteFrom(long index);

    TxResult proveWithdrawal(Withdrawal w, long outputIndex, List<Bytes32> proof);

    TxResult finalizeWithdrawal(Withdrawal w);

    TxResult pause();

    TxResult unpause();
}
