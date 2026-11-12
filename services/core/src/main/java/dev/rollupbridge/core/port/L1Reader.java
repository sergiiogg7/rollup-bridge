package dev.rollupbridge.core.port;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.model.DepositEvent;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.ProofInfo;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/** Read side of the L1 gateway (port). Implemented by an RPC adapter or by a test fake. */
public interface L1Reader {
    long headNumber();

    /** Hash of the canonical block at {@code number}, empty if the chain is shorter. */
    Optional<Bytes32> canonicalHash(long number);

    /** Timestamp of the latest block (the contracts use block time, not wall-clock time). */
    long headTimestamp();

    /** {@code DepositInitiated} logs in [from, to], each carrying the hash of the block it was found in. */
    List<DepositEvent> depositEvents(long fromBlock, long toBlock);

    long outputCount();

    OutputRecord output(long index);

    /** Newest output that is not deleted, if any. */
    Optional<OutputRecord> latestLiveOutput();

    boolean isFinalizable(long outputIndex);

    ProofInfo proof(Bytes32 leaf);

    boolean isFinalized(Bytes32 leaf);

    boolean isPaused();

    /** Seconds an output stays challengeable ({@code OutputOracle.CHALLENGE_WINDOW}). */
    long challengeWindow();

    BigInteger proposerBond();

    BigInteger challengerBond();

    BigInteger claimable(String account);
}
