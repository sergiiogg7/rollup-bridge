package dev.rollupbridge.services;

import dev.rollupbridge.core.port.GatewayException;

/**
 * Maps an exception to a small, fixed set of reasons, so that the {@code reason} label of
 * {@code bridge_service_errors_total} never explodes in cardinality (never put a message or an id in a label).
 */
public final class ErrorReasons {
    public static final String RPC_TRANSPORT = "rpc_transport";
    public static final String RPC_ERROR = "rpc_error";
    public static final String TX_REVERTED = "tx_reverted";
    public static final String TX_TIMEOUT = "tx_timeout";
    public static final String STATE_MACHINE = "state_machine";
    public static final String INTERNAL = "internal";

    private ErrorReasons() {}

    public static String of(Throwable t) {
        if (t instanceof GatewayException g) {
            String m = String.valueOf(g.getMessage());
            if (m.startsWith("reverted:") || g.revertData() != null) {
                return TX_REVERTED;
            }
            if (m.contains("transport error") || m.contains("interrupted")) {
                return RPC_TRANSPORT;
            }
            if (m.contains("not mined in time")) {
                return TX_TIMEOUT;
            }
            return RPC_ERROR;
        }
        if (t instanceof IllegalStateException && String.valueOf(t.getMessage()).contains("illegal")) {
            return STATE_MACHINE;
        }
        return INTERNAL;
    }
}
