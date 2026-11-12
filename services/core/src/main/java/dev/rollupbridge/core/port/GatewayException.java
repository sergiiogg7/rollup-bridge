package dev.rollupbridge.core.port;

/** Anything that goes wrong talking to a chain: transport error, JSON-RPC error, reverted call or transaction. */
public class GatewayException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String revertData;

    public GatewayException(String message) {
        this(message, null, null);
    }

    public GatewayException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public GatewayException(String message, String revertData, Throwable cause) {
        super(message, cause);
        this.revertData = revertData;
    }

    /** Hex revert data (selector + args) when the node reported a revert, else null. */
    public String revertData() {
        return revertData;
    }
}
