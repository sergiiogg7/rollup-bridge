package dev.rollupbridge.core.model;

import java.util.List;

/** A mined transaction: hash, success flag and the raw logs (topics/data) for event decoding. */
public record TxResult(String txHash, boolean success, long blockNumber, List<LogEntry> logs, String contractAddress) {

    /** A log with hex topics and data. */
    public record LogEntry(String address, List<String> topics, String data) {}
}
