package me.tibetty.sigbrute.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Keccak256Util {

    // Keccak-f[1600] round constants
    private static final long[] RC = {0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL,
        0x8000000080008000L, 0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L,
        0x8000000000008009L, 0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L,
        0x000000008000000AL, 0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L,
        0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL,
        0x800000008000000AL, 0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L,
        0x8000000080008008L};

    // ρ rotation offsets and π permutation indices (combined ρπ traversal)
    private static final int[] RHO = {1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25,
        43, 62, 18, 39, 61, 20, 44};
    private static final int[] PI = {10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12,
        2, 20, 14, 22, 9, 6, 1};

    // Rate for Keccak-256: r = 1600 - 2*256 = 1088 bits = 136 bytes
    private static final int RATE_BYTES = 136;

    private Keccak256Util() {
    }

    public static byte[] hash(String input) {
        return hash(input.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] hash(byte[] input) {
        // Keccak padding: 0x01 at end of message, 0x80 at end of last block (NOT SHA-3's 0x06)
        var paddedLen = input.length + RATE_BYTES - (input.length % RATE_BYTES);
        var padded = Arrays.copyOf(input, paddedLen);
        padded[input.length] = 0x01;
        padded[paddedLen - 1] |= (byte) 0x80;

        var state = new long[25];
        for (var i = 0; i < paddedLen; i += RATE_BYTES) {
            for (var j = 0; j < RATE_BYTES / 8; j++) {
                state[j] ^= loadLE64(padded, i + j * 8);
            }
            keccakF(state);
        }

        var out = new byte[32];
        for (int i = 0; i < 4; i++) {
            storeLE64(out, i * 8, state[i]);
        }
        return out;
    }

    public static boolean selectorMatches(String sig, byte[] selector4) {
        var h = hash(sig);
        return h[0] == selector4[0] && h[1] == selector4[1] && h[2] == selector4[2]
            && h[3] == selector4[3];
    }

    private static void keccakF(long[] s) {
        var bc = new long[5];
        for (var round = 0; round < 24; round++) {
            // θ
            for (var i = 0; i < 5; i++) {
                bc[i] = s[i] ^ s[i + 5] ^ s[i + 10] ^ s[i + 15] ^ s[i + 20];
            }
            for (var i = 0; i < 5; i++) {
                var d = bc[(i + 4) % 5] ^ rotl(bc[(i + 1) % 5], 1);
                for (var j = 0; j < 25; j += 5) {
                    s[j + i] ^= d;
                }
            }

            // ρπ (combined via pointer-chasing to avoid a temporary 5×5 copy)
            var t = s[1];
            for (var i = 0; i < 24; i++) {
                var j = PI[i];
                var tmp = s[j];
                s[j] = rotl(t, RHO[i]);
                t = tmp;
            }

            // χ
            for (var j = 0; j < 25; j += 5) {
                var b0 = s[j];
                var b1 = s[j + 1];
                var b2 = s[j + 2];
                var b3 = s[j + 3];
                var b4 = s[j + 4];
                s[j] = b0 ^ (~b1 & b2);
                s[j + 1] = b1 ^ (~b2 & b3);
                s[j + 2] = b2 ^ (~b3 & b4);
                s[j + 3] = b3 ^ (~b4 & b0);
                s[j + 4] = b4 ^ (~b0 & b1);
            }

            // ι
            s[0] ^= RC[round];
        }
    }

    private static long rotl(long v, int n) {
        return (v << n) | (v >>> (64 - n));
    }

    private static long loadLE64(byte[] b, int o) {
        return (b[o] & 0xFF) | ((long) (b[o + 1] & 0xFF) << 8) | ((long) (b[o + 2] & 0xFF) << 16)
            | ((long) (b[o + 3] & 0xFF) << 24) | ((long) (b[o + 4] & 0xFF) << 32)
            | ((long) (b[o + 5] & 0xFF) << 40) | ((long) (b[o + 6] & 0xFF) << 48)
            | ((long) (b[o + 7] & 0xFF) << 56);
    }

    private static void storeLE64(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
        b[o + 4] = (byte) (v >> 32);
        b[o + 5] = (byte) (v >> 40);
        b[o + 6] = (byte) (v >> 48);
        b[o + 7] = (byte) (v >> 56);
    }
}
