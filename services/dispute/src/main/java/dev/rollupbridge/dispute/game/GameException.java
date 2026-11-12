package dev.rollupbridge.dispute.game;

/**
 * A move the game refuses. {@link #error()} is the name of the Solidity custom error ({@code WrongPhase},
 * {@code NotProposer}, ...), so the in-memory model and the real contract can be compared outcome by outcome.
 */
public class GameException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String error;

    public GameException(String error) {
        super(error);
        this.error = error;
    }

    public GameException(String error, String detail) {
        super(error + ": " + detail);
        this.error = error;
    }

    public String error() {
        return error;
    }
}
