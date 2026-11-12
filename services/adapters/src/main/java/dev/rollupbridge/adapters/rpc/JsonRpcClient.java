package dev.rollupbridge.adapters.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rollupbridge.core.port.GatewayException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal JSON-RPC 2.0 client over {@link java.net.http.HttpClient}. Deliberately tiny: the bridge needs a
 * dozen methods, not a whole web3 SDK (web3j is used only for secp256k1 signing, see {@link Signer}).
 */
public final class JsonRpcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI uri;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicLong ids = new AtomicLong();

    public JsonRpcClient(String url) {
        this.uri = URI.create(url);
    }

    public String url() {
        return uri.toString();
    }

    /** Calls {@code method}; returns the {@code result} node (may be a JSON null). */
    public JsonNode call(String method, Object... params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", ids.incrementAndGet());
        req.put("method", method);
        ArrayNode arr = req.putArray("params");
        for (Object p : params) {
            arr.add(MAPPER.valueToTree(p));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(req.toString()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = MAPPER.readTree(response.body());
            JsonNode error = body.get("error");
            if (error != null && !error.isNull()) {
                JsonNode data = error.get("data");
                String revertData = data != null && data.isTextual() ? data.asText() : null;
                throw new GatewayException(method + " failed: " + error.path("message").asText(error.toString()), revertData, null);
            }
            return body.get("result");
        } catch (IOException e) {
            throw new GatewayException(method + " transport error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(method + " interrupted", e);
        }
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }
}
