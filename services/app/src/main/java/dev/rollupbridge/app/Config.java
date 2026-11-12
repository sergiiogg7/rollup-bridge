package dev.rollupbridge.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Configuration from environment variables only (keys never touch a file in the repo). Contract addresses can
 * also come from {@code deployments/l1.json} and {@code deployments/l2.json}, which the Foundry deploy scripts write.
 * All keys used by the demo are Anvil public TEST keys.
 */
public record Config(
        String l1Rpc,
        String l2Rpc,
        long l1ChainId,
        long l2ChainId,
        String outputOracle,
        String l1Bridge,
        String l2Bridge,
        String relayerKey,
        String proposerKey,
        String challengerKey,
        String guardianKey,
        long depositConfirmations,
        long l1StartBlock,
        long pollMillis,
        long challengerPollMillis,
        Path dbDir,
        int statusPort) {

    public static Config fromEnv() {
        Map<String, String> env = System.getenv();
        Path deployments = Path.of(env.getOrDefault("DEPLOYMENTS_DIR", "deployments"));
        JsonNode l1 = read(deployments.resolve("l1.json"));
        JsonNode l2 = read(deployments.resolve("l2.json"));
        return new Config(
                env.getOrDefault("L1_RPC", "http://127.0.0.1:8545"),
                env.getOrDefault("L2_RPC", "http://127.0.0.1:9545"),
                Long.parseLong(env.getOrDefault("L1_CHAIN_ID", "31337")),
                Long.parseLong(env.getOrDefault("L2_CHAIN_ID", "901")),
                pick(env, "OUTPUT_ORACLE", l1, "outputOracle"),
                pick(env, "L1_BRIDGE", l1, "l1Bridge"),
                pick(env, "L2_BRIDGE", l2, "l2Bridge"),
                env.get("RELAYER_KEY"),
                env.get("PROPOSER_KEY"),
                env.get("CHALLENGER_KEY"),
                env.get("GUARDIAN_KEY"),
                Long.parseLong(env.getOrDefault("DEPOSIT_CONFIRMATIONS", "2")),
                Long.parseLong(env.getOrDefault("L1_START_BLOCK", l1 != null && l1.has("deployBlock") ? l1.get("deployBlock").asText() : "0")),
                Long.parseLong(env.getOrDefault("POLL_MS", "1000")),
                Long.parseLong(env.getOrDefault("CHALLENGER_POLL_MS", env.getOrDefault("POLL_MS", "1000"))),
                Path.of(env.getOrDefault("DB_DIR", ".data")),
                Integer.parseInt(env.getOrDefault("STATUS_PORT", "0")));
    }

    private static JsonNode read(Path file) {
        try {
            return Files.exists(file) ? new ObjectMapper().readTree(file.toFile()) : null;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static String pick(Map<String, String> env, String variable, JsonNode json, String field) {
        String v = env.get(variable);
        if (v != null && !v.isBlank()) {
            return v;
        }
        if (json != null && json.hasNonNull(field)) {
            return json.get(field).asText();
        }
        throw new IllegalStateException("missing " + variable + " (set it, or run the deploy script that writes deployments/*.json)");
    }

    public String require(String key, String name) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("missing " + name + " (a throw-away test key; see .env.example)");
        }
        return key;
    }
}
