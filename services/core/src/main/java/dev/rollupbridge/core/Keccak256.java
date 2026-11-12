package dev.rollupbridge.core;

import java.nio.charset.StandardCharsets;

/**
 * Keccak-256 as used by Ethereum (the original padding byte 0x01, NOT the NIST SHA3-256 byte 0x06).
 *
 * <p>Implemented here so that {@code core} has zero dependencies. Verified against the empty-input and
 * "abc" vectors and, indirectly, against every Solidity vector in the differential tests.
 */
public final class Keccak256 {
    private static final int RATE_BYTES = 136; // 1600 - 2 * 256 bits

    private static final long[] ROUND_CONSTANTS = {
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
        0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
        0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
        0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    /** Rotation offsets, indexed [x + 5 * y]. */
    private static final int[] ROTATIONS = {
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14
    };

    private Keccak256() {}

    public static byte[] hash(byte[] input) {
        long[] state = new long[25];
        int offset = 0;
        while (input.length - offset >= RATE_BYTES) {
            absorbBlock(state, input, offset, RATE_BYTES);
            offset += RATE_BYTES;
        }
        byte[] last = new byte[RATE_BYTES];
        int rest = input.length - offset;
        System.arraycopy(input, offset, last, 0, rest);
        last[rest] ^= 0x01; // Keccak padding (SHA3 would use 0x06)
        last[RATE_BYTES - 1] ^= (byte) 0x80;
        absorbBlock(state, last, 0, RATE_BYTES);

        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = (byte) (state[i / 8] >>> (8 * (i % 8)));
        }
        return out;
    }

    public static byte[] hash(byte[] a, byte[] b) {
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return hash(joined);
    }

    public static byte[] hash(String utf8) {
        return hash(utf8.getBytes(StandardCharsets.UTF_8));
    }

    private static void absorbBlock(long[] state, byte[] data, int offset, int length) {
        for (int i = 0; i < length / 8; i++) {
            long lane = 0;
            for (int b = 7; b >= 0; b--) {
                lane = (lane << 8) | (data[offset + i * 8 + b] & 0xffL);
            }
            state[i] ^= lane;
        }
        permute(state);
    }

    private static void permute(long[] a) {
        long[] c = new long[5];
        long[] b = new long[25];
        for (int round = 0; round < 24; round++) {
            // theta
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) {
                    a[x + y] ^= d;
                }
            }
            // rho and pi
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Long.rotateLeft(a[x + 5 * y], ROTATIONS[x + 5 * y]);
                }
            }
            // chi
            for (int y = 0; y < 25; y += 5) {
                for (int x = 0; x < 5; x++) {
                    a[x + y] = b[x + y] ^ (~b[(x + 1) % 5 + y] & b[(x + 2) % 5 + y]);
                }
            }
            // iota
            a[0] ^= ROUND_CONSTANTS[round];
        }
    }
}
