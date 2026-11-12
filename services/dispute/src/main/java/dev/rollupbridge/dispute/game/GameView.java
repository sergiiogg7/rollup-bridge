package dev.rollupbridge.dispute.game;

import dev.rollupbridge.core.Bytes32;

/** A snapshot of one game, the same fields as the Solidity {@code BisectionGame.Game} struct. */
public record GameView(
        String proposer,
        String challenger,
        Phase phase,
        Resolution resolution,
        boolean proposerWon,
        long lo,
        long hi,
        long deadline,
        Bytes32 loHash,
        Bytes32 hiHash,
        Bytes32 midHash) {

    public static final String NO_ADDRESS = "0x0000000000000000000000000000000000000000";

    /** Same order as the Solidity enum (the ordinal is the ABI value). */
    public enum Phase {
        NONE,
        AWAITING_CHALLENGE,
        AWAITING_BISECT,
        AWAITING_RESPONSE,
        AWAITING_STEP,
        RESOLVED
    }

    /** Same order as the Solidity enum. */
    public enum Resolution {
        NONE,
        UNCHALLENGED,
        ONE_STEP_PROOF,
        TIMEOUT
    }

    public boolean resolved() {
        return phase == Phase.RESOLVED;
    }

    /** The step the proposer's next {@code bisect} must name. */
    public long expectedStep() {
        return lo + (hi - lo) / 2;
    }
}
