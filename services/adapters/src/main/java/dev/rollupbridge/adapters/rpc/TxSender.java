package dev.rollupbridge.adapters.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.GatewayException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds, signs, sends and waits for one transaction at a time from one key. The sequence is the classic one:
 * nonce, gas price, gas estimation (which doubles as a revert check: a call that would revert never costs gas),
 * sign, send, poll the receipt.
 */
public final class TxSender {
    private static final BigInteger GAS_MARGIN_NUM = BigInteger.valueOf(13);
    private static final BigInteger GAS_MARGIN_DEN = BigInteger.TEN;

    private final JsonRpcClient rpc;
    private final Signer signer;
    private final long chainId;

    public TxSender(JsonRpcClient rpc, Signer signer, long chainId) {
        this.rpc = rpc;
        this.signer = signer;
        this.chainId = chainId;
    }

    public String address() {
        return signer.address();
    }

    /** Sends a call (or a contract creation when {@code to} is null) and waits until it is mined. */
    public TxResult send(String to, BigInteger value, byte[] data) {
        String from = signer.address();
        BigInteger gasLimit = estimate(from, to, value, data);
        BigInteger nonce = Hex.quantityToBigInteger(rpc.call("eth_getTransactionCount", from, "pending").asText());
        BigInteger gasPrice = gasPrice();
        String raw = signer.sign(chainId, nonce, gasPrice, gasLimit, to, value, data);
        String hash = rpc.call("eth_sendRawTransaction", raw).asText();
        return awaitReceipt(hash);
    }

    /**
     * Legacy gas price = 2 x the latest base fee + 1 gwei tip. Deliberately NOT derived from {@code eth_gasPrice}:
     * on Anvil that value follows recent transaction prices, so doubling it per transaction snowballs.
     */
    private BigInteger gasPrice() {
        JsonNode latest = rpc.call("eth_getBlockByNumber", "latest", false);
        BigInteger base = latest.hasNonNull("baseFeePerGas")
                ? Hex.quantityToBigInteger(latest.get("baseFeePerGas").asText())
                : BigInteger.ZERO;
        return base.multiply(BigInteger.TWO).add(BigInteger.valueOf(1_000_000_000L));
    }

    private BigInteger estimate(String from, String to, BigInteger value, byte[] data) {
        var tx = JsonRpcClient.object();
        tx.put("from", from);
        if (to != null) {
            tx.put("to", to);
        }
        tx.put("value", Hex.toQuantity(value));
        tx.put("data", Hex.encode(data));
        try {
            BigInteger est = Hex.quantityToBigInteger(rpc.call("eth_estimateGas", tx).asText());
            return est.multiply(GAS_MARGIN_NUM).divide(GAS_MARGIN_DEN);
        } catch (GatewayException e) {
            throw new GatewayException(
                    "reverted: " + ErrorNames.describe(e.revertData(), e.getMessage()), e.revertData(), e);
        }
    }

    private TxResult awaitReceipt(String hash) {
        for (int i = 0; i < 300; i++) {
            JsonNode r = rpc.call("eth_getTransactionReceipt", hash);
            if (r != null && !r.isNull()) {
                boolean ok = "0x1".equals(r.path("status").asText());
                List<TxResult.LogEntry> logs = new ArrayList<>();
                for (JsonNode l : r.path("logs")) {
                    List<String> topics = new ArrayList<>();
                    l.path("topics").forEach(t -> topics.add(t.asText()));
                    logs.add(new TxResult.LogEntry(l.path("address").asText(), topics, l.path("data").asText()));
                }
                JsonNode created = r.path("contractAddress");
                return new TxResult(
                        hash, ok, Hex.quantityToLong(r.path("blockNumber").asText()), logs,
                        created.isNull() || created.isMissingNode() ? null : created.asText());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GatewayException("interrupted while waiting for " + hash, e);
            }
        }
        throw new GatewayException("transaction " + hash + " was not mined in time");
    }
}
