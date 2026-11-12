package dev.rollupbridge.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny Solidity ABI codec: exactly the types the bridge contracts use (uint, address, bool, bytes32,
 * static tuples, bytes32[]). Analogy for a Java developer: this is the "serializer" between Java objects
 * and the byte format the EVM expects, like Protobuf but with 32-byte words.
 */
public final class Abi {
    private Abi() {}

    /** A value that can be ABI-encoded. */
    public sealed interface Value {
        boolean isDynamic();
    }

    public record Uint(BigInteger value) implements Value {
        public Uint {
            if (value.signum() < 0 || value.bitLength() > 256) {
                throw new IllegalArgumentException("uint256 out of range: " + value);
            }
        }

        @Override
        public boolean isDynamic() {
            return false;
        }
    }

    public record Address(String hex) implements Value {
        public Address {
            hex = Hex.address(hex);
        }

        @Override
        public boolean isDynamic() {
            return false;
        }
    }

    public record Bool(boolean value) implements Value {
        @Override
        public boolean isDynamic() {
            return false;
        }
    }

    public record B32(Bytes32 value) implements Value {
        @Override
        public boolean isDynamic() {
            return false;
        }
    }

    /** A tuple of static members, encoded inline (as the ABI specifies for static structs). */
    public record Tuple(List<Value> members) implements Value {
        @Override
        public boolean isDynamic() {
            return members.stream().anyMatch(Value::isDynamic);
        }
    }

    public record B32Array(List<Bytes32> items) implements Value {
        @Override
        public boolean isDynamic() {
            return true;
        }
    }

    public static Uint uint(long v) {
        return new Uint(BigInteger.valueOf(v));
    }

    public static Uint uint(BigInteger v) {
        return new Uint(v);
    }

    public static Address address(String hex) {
        return new Address(hex);
    }

    public static B32 bytes32(Bytes32 b) {
        return new B32(b);
    }

    /** 4-byte function selector, e.g. {@code selector("depositETH(address)")}. */
    public static byte[] selector(String signature) {
        byte[] h = Keccak256.hash(signature);
        return new byte[] {h[0], h[1], h[2], h[3]};
    }

    /** Selector plus ABI-encoded arguments, ready to use as transaction {@code data}. */
    public static byte[] encodeCall(String signature, Value... args) {
        byte[] sel = selector(signature);
        byte[] body = encode(List.of(args));
        byte[] out = new byte[sel.length + body.length];
        System.arraycopy(sel, 0, out, 0, sel.length);
        System.arraycopy(body, 0, out, sel.length, body.length);
        return out;
    }

    /** Standard head/tail encoding of a parameter list. */
    public static byte[] encode(List<Value> values) {
        int headSize = 0;
        for (Value v : values) {
            headSize += headWords(v) * 32;
        }
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        for (Value v : values) {
            if (v.isDynamic()) {
                head.writeBytes(word(BigInteger.valueOf((long) headSize + tail.size())));
                tail.writeBytes(encodeDynamic(v));
            } else {
                head.writeBytes(encodeStatic(v));
            }
        }
        head.writeBytes(tail.toByteArray());
        return head.toByteArray();
    }

    private static int headWords(Value v) {
        if (v.isDynamic()) {
            return 1;
        }
        if (v instanceof Tuple t) {
            return t.members().stream().mapToInt(Abi::headWords).sum();
        }
        return 1;
    }

    private static byte[] encodeStatic(Value v) {
        return switch (v) {
            case Uint u -> word(u.value());
            case Address a -> word(new BigInteger(Hex.strip(a.hex()), 16));
            case Bool b -> word(b.value() ? BigInteger.ONE : BigInteger.ZERO);
            case B32 b -> b.value().toBytes();
            case Tuple t -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (Value m : t.members()) {
                    out.writeBytes(encodeStatic(m));
                }
                yield out.toByteArray();
            }
            case B32Array ignored -> throw new IllegalStateException("dynamic value in static position");
        };
    }

    private static byte[] encodeDynamic(Value v) {
        if (v instanceof B32Array arr) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(word(BigInteger.valueOf(arr.items().size())));
            for (Bytes32 b : arr.items()) {
                out.writeBytes(b.toBytes());
            }
            return out.toByteArray();
        }
        throw new IllegalStateException("unsupported dynamic value: " + v);
    }

    static byte[] word(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int start = raw[0] == 0 && raw.length > 1 ? 1 : 0;
        int len = raw.length - start;
        System.arraycopy(raw, start, out, 32 - len, len);
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Decoding
    // ---------------------------------------------------------------------------------------------

    /** Read-only view over return data / log data as 32-byte words. */
    public static final class Words {
        private final byte[] data;

        public Words(byte[] data) {
            this.data = data;
        }

        public static Words of(String hex) {
            return new Words(Hex.decode(hex));
        }

        public int count() {
            return data.length / 32;
        }

        public BigInteger uint(int i) {
            return new BigInteger(1, slice(i));
        }

        public long uintAsLong(int i) {
            return uint(i).longValueExact();
        }

        public boolean bool(int i) {
            return uint(i).signum() != 0;
        }

        public String address(int i) {
            byte[] w = slice(i);
            return Hex.encode(java.util.Arrays.copyOfRange(w, 12, 32));
        }

        public Bytes32 bytes32(int i) {
            return Bytes32.of(slice(i));
        }

        private byte[] slice(int i) {
            if (i < 0 || (i + 1) * 32 > data.length) {
                throw new IllegalArgumentException("word " + i + " out of range (" + data.length + " bytes)");
            }
            return java.util.Arrays.copyOfRange(data, i * 32, (i + 1) * 32);
        }
    }

    /** Splits a topic or word list helper: converts an indexed topic (32-byte hex) to an address. */
    public static String topicToAddress(String topic) {
        return Hex.address("0x" + Hex.strip(topic).substring(24));
    }

    public static List<Bytes32> bytes32List(List<String> hex) {
        List<Bytes32> out = new ArrayList<>();
        for (String h : hex) {
            out.add(Bytes32.of(h));
        }
        return out;
    }
}
