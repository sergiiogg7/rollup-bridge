package dev.rollupbridge.core.model;

import dev.rollupbridge.core.Bytes32;

/** An output posted on L1 ({@code struct Output} plus its index). */
public record OutputRecord(
        long index,
        Bytes32 withdrawalRoot,
        Bytes32 l2BlockHash,
        long l2BlockNumber,
        long withdrawalCount,
        long proposedAt,
        OutputStatus status,
        String proposer,
        String challenger,
        long challengedAt,
        boolean bondSettled) {}
