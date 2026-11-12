package dev.rollupbridge.core;

import java.math.BigInteger;
import java.util.Locale;

/** Hex helpers. Everything is lower-case and 0x-prefixed on the way out, tolerant on the way in. */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String encode(byte[] bytes) {
        char[] out = new char[2 + bytes.length * 2];
        out[0] = '0';
        out[1] = 'x';
        for (int i = 0; i < bytes.length; i++) {
            out[2 + i * 2] = DIGITS[(bytes[i] >> 4) & 0xf];
            out[3 + i * 2] = DIGITS[bytes[i] & 0xf];
        }
        return new String(out);
    }

    public static byte[] decode(String hex) {
        String h = strip(hex);
        if (h.length() % 2 != 0) {
            h = "0" + h;
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("not hex: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String strip(String hex) {
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

    /** Parses a JSON-RPC quantity ("0x1f") into a long. */
    public static long quantityToLong(String q) {
        return new BigInteger(strip(q).isEmpty() ? "0" : strip(q), 16).longValueExact();
    }

    public static BigInteger quantityToBigInteger(String q) {
        return new BigInteger(strip(q).isEmpty() ? "0" : strip(q), 16);
    }

    public static String toQuantity(long v) {
        return "0x" + Long.toHexString(v);
    }

    public static String toQuantity(BigInteger v) {
        return "0x" + v.toString(16);
    }

    /** Lower-case, 0x-prefixed 20-byte address; throws on anything else. */
    public static String address(String a) {
        String h = strip(a).toLowerCase(Locale.ROOT);
        if (h.length() != 40) {
            throw new IllegalArgumentException("not an address: " + a);
        }
        return "0x" + h;
    }
}
