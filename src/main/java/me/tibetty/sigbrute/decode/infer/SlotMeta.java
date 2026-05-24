package me.tibetty.sigbrute.decode.infer;

import java.util.Arrays;

/**
 * Pre-computed analysis of a single 32-byte ABI head slot.
 *
 * <p>
 * Every byte-scan that multiple {@link SlotInferrer} implementations would otherwise repeat is
 * performed once here and stored as compact fields. All inferrers read from this record rather than
 * re-scanning the raw byte array.
 *
 * <p>
 * Construct via {@link #of(byte[])}; the canonical constructor is package-private only.
 */
record SlotMeta(
    byte[] word,
    /** Index of the first non-zero byte, or 32 when the entire word is zero. */
    int firstNonZero,
    /** Index of the last non-zero byte, or -1 when the entire word is zero. */
    int lastNonZero,
    /** Number of leading {@code 0xFF} bytes (0 to 32). */
    int ffRun,
    /**
     * {@code true} when the non-zero bytes form at least two separated clusters (one or more zero
     * bytes between them). Indicates a packed-{@code bytes32} layout such as two {@code uint128}
     * halves.
     */
    boolean hasGap,
    /**
     * {@code true} when ≥ half of the 20 data bytes in {@code word[12..32)} are non-zero. Used as a
     * Keccak-256 entropy proxy to distinguish real addresses from small integer casts.
     */
    boolean highEntropy) {

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Scans {@code word} once and returns a fully initialised {@link SlotMeta}.
     *
     * @throws IllegalArgumentException if {@code word} is not exactly 32 bytes
     */
    static SlotMeta of(byte[] word) {
        if (word.length != 32) {
            throw new IllegalArgumentException("word must be 32 bytes");
        }

        var forward = scanForward(word);
        var lastNonZero = scanLastNonZero(word);
        var hasGap = detectInteriorGap(word, forward.firstNonZero());
        var highEntropy = isHighEntropyAddressBody(word);

        return new SlotMeta(word, forward.firstNonZero(), lastNonZero, forward.ffRun(), hasGap,
            highEntropy);
    }

    private record ForwardScan(int firstNonZero, int ffRun) {
    }

    /** Forward scan: {@code firstNonZero} and leading {@code 0xFF} run length. */
    private static ForwardScan scanForward(byte[] word) {
        var firstNonZero = 32;
        var ffRun = 0;
        for (var i = 0; i < 32; i++) {
            var b = word[i] & 0xFF;
            if (firstNonZero == 32 && b != 0) {
                firstNonZero = i;
            }
            if (i == ffRun && b == 0xFF) {
                ffRun = i + 1;
            }
        }
        return new ForwardScan(firstNonZero, ffRun);
    }

    private static int scanLastNonZero(byte[] word) {
        for (var i = 31; i >= 0; i--) {
            if (word[i] != 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code true} when non-zero bytes from {@code firstNonZero} form at least two separated
     * clusters.
     */
    private static boolean detectInteriorGap(byte[] word, int firstNonZero) {
        if (firstNonZero >= 32) {
            return false;
        }

        var seenNonZero = false;
        var seenZeroAfterNonZero = false;
        for (var i = firstNonZero; i < 32; i++) {
            if (word[i] != 0) {
                if (seenZeroAfterNonZero) {
                    return true;
                }
                seenNonZero = true;
            } else if (seenNonZero) {
                seenZeroAfterNonZero = true;
            }
        }
        return false;
    }

    /** {@code true} when ≥ 10 of the 20 bytes in {@code word[12..32)} are non-zero. */
    private static boolean isHighEntropyAddressBody(byte[] word) {
        var nonZeroInBody = 0;
        for (var i = 12; i < 32; i++) {
            if (word[i] != 0) {
                nonZeroInBody++;
            }
        }
        return nonZeroInBody >= 10;
    }

    // ── Derived predicates ────────────────────────────────────────────────────

    /** {@code true} when every byte is zero. */
    boolean isAllZero() {
        return firstNonZero == 32;
    }

    /** {@code true} when byte 0 is non-zero (the slot is left-aligned). */
    boolean isLeftAligned() {
        return firstNonZero == 0;
    }

    /**
     * {@code true} when the slot occupies all 32 bytes (byte 0 and byte 31 are both non-zero).
     * Implies {@link #isLeftAligned()}.
     */
    boolean isFullWord() {
        return firstNonZero == 0 && lastNonZero == 31;
    }

    /**
     * {@code true} when the slot has exactly 12 leading zero bytes, the classic 20-byte address
     * layout.
     */
    boolean isAddressShaped() {
        return firstNonZero == 12;
    }

    /**
     * {@code true} when the slot has exactly one non-zero byte at position 31 with value 1 (encodes
     * {@code bool(true)}, {@code uint8(1)}, {@code int8(1)}, etc.).
     */
    boolean isValueOne() {
        return firstNonZero == 31 && (word[31] & 0xFF) == 1;
    }

    /**
     * Minimum ABI integer bit-width required to encode the value in this slot without truncation:
     * {@code (32 − firstNonZero) × 8}.
     */
    int minBits() {
        return (32 - firstNonZero) * 8;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof SlotMeta other)) {
            return false;
        }

        return firstNonZero == other.firstNonZero && lastNonZero == other.lastNonZero
            && ffRun == other.ffRun && hasGap == other.hasGap && highEntropy == other.highEntropy
            && Arrays.equals(word, other.word);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(word);
        result = 31 * result + firstNonZero;
        result = 31 * result + lastNonZero;
        result = 31 * result + ffRun;
        result = 31 * result + Boolean.hashCode(hasGap);
        result = 31 * result + Boolean.hashCode(highEntropy);
        return result;
    }

    @Override
    public String toString() {
        return "SlotMeta[word=" + Arrays.toString(word) + ", firstNonZero=" + firstNonZero
            + ", lastNonZero=" + lastNonZero + ", ffRun=" + ffRun + ", hasGap=" + hasGap
            + ", highEntropy=" + highEntropy + "]";
    }
}
