package dev.rollupbridge.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.dispute.vm.VmState;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates and parses {@code testdata/dispute-vectors.json}, which the Solidity script generates. */
final class TestData {
    private TestData() {}

    static JsonNode vectors() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("testdata/dispute-vectors.json"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("testdata/dispute-vectors.json not found above the working directory");
        }
        try {
            return new ObjectMapper().readTree(dir.resolve("testdata/dispute-vectors.json").toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses {@code {"pc":"..","regs":["..",".."]}} where every number is an unsigned decimal string. */
    static VmState state(JsonNode n) {
        long[] regs = new long[4];
        for (int i = 0; i < 4; i++) {
            regs[i] = u64(n.get("regs").get(i).asText());
        }
        return VmState.of(u64(n.get("pc").asText()), regs);
    }

    static long u64(String decimal) {
        BigInteger v = new BigInteger(decimal);
        if (v.bitLength() > 64) {
            throw new IllegalArgumentException("not a uint64: " + decimal);
        }
        return v.longValue();
    }

    static Bytes32 hash(JsonNode n) {
        return Bytes32.of(n.asText());
    }
}
