package dev.rollupbridge.core;

import java.util.Arrays;

/** An immutable 32-byte value (hash, tree node, Merkle root). Value semantics, unlike a raw {@code byte[]}. */
public final class Bytes32 implements Comparable<Bytes32> {
    public static final Bytes32 ZERO = new Bytes32(new byte[32]);

    private final byte[] value;

    private Bytes32(byte[] value) {
        this.value = value;
    }

    public static Bytes32 of(byte[] bytes) {
        if (bytes.length != 32) {
            throw new IllegalArgumentException("expected 32 bytes, got " + bytes.length);
        }
        return new Bytes32(bytes.clone());
    }

    public static Bytes32 of(String hex) {
        return of(Hex.decode(hex));
    }

    public byte[] toBytes() {
        return value.clone();
    }

    /** Package/Module internal fast path for hashing without defensive copies. */
    byte[] raw() {
        return value;
    }

    public String hex() {
        return Hex.encode(value);
    }

    @Override
    public int compareTo(Bytes32 other) {
        return Arrays.compareUnsigned(value, other.value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes32 other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return hex();
    }
}
