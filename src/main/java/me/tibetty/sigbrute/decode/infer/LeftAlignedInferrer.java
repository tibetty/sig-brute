package me.tibetty.sigbrute.decode.infer;

import java.util.List;

/**
 * Infers ABI-type candidates for a left-aligned 32-byte head slot (byte 0 is non-zero).
 *
 * <p>
 * Left-aligned slots belong to the {@code bytesN} family. {@code uint*} and {@code address} are
 * right-aligned and cannot produce a non-zero high byte, so they are excluded here. However,
 * <em>negative</em> {@code intN} values are sign-extended with leading {@code 0xFF} bytes and
 * therefore also appear left-aligned. This inferrer detects the sign-extension pattern and adds
 * an {@code intN+} floor candidate when the pattern is clean.
 *
 * <p>
 * Sub-cases:
 * <ul>
 *   <li><b>Full word</b> ({@code lastNonZero == 31}): every 32-byte pattern is a valid
 *       {@code bytes32} and {@code uint256}; also a valid {@code int256}. If a clean sign-extension
 *       run is detected the tighter {@code intN+} floor is used instead of {@code int256}.
 *   <li><b>bytes24 shape</b> ({@code lastNonZero == 23}): the {@code function} type encodes as a
 *       20-byte address concatenated with a 4-byte selector, left-aligned with 8 trailing zeros —
 *       indistinguishable from {@code bytes24} at the binary level but produces a different
 *       function selector in sig-brute.
 *   <li><b>Other trailing zeros</b>: the tightest {@code bytesN} is the primary candidate. A clean
 *       sign-extension pattern adds an {@code intN+} floor.
 * </ul>
 */
final class LeftAlignedInferrer implements SlotInferrer {

    @Override
    public List<String> infer(SlotMeta meta) {
        if (!meta.isLeftAligned()) {
            return List.of();
        }

        var intCand = signExtendedIntCandidate(meta);

        if (meta.isFullWord()) {
            // Every 32-byte pattern fits bytes32 and uint256; all patterns are also valid int256.
            // If a tighter sign-extension floor is detectable, prefer it over the bare "int256".
            var intFull = intCand != null ? intCand : "int256";
            return List.of("bytes32", "uint256", intFull);
        }

        // Trailing zeros: tightest bytesN is lastNonZero + 1
        var bytesN = "bytes" + (meta.lastNonZero() + 1);

        if (meta.lastNonZero() == 23) {
            // bytes24 shape: "function" (20-byte address || 4-byte selector) is also a candidate.
            if (intCand != null) {
                return List.of(bytesN, "function", intCand);
            }
            return List.of(bytesN, "function");
        }

        if (intCand != null) {
            return List.of(bytesN, intCand);
        }
        return List.of(bytesN);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns an {@code intN+} floor pattern when {@code meta}'s slot looks like a sign-extended
     * negative integer, {@code null} otherwise.
     *
     * <p>
     * Signature for a negative {@code intN}: a leading run of {@code 0xFF} bytes followed by the
     * first non-{@code 0xFF} byte having its high bit set (the most-significant byte of the actual
     * value is negative). Special case: all 32 bytes are {@code 0xFF} (encodes {@code -1} in any
     * {@code intN}) → returns {@code "int*"}.
     *
     * <p>
     * Limitation: values like {@code int32(-256) = 0xFF…FF00} are NOT detected because the first
     * non-{@code 0xFF} byte ({@code 0x00}) does not have its high bit set.
     */
    private static String signExtendedIntCandidate(SlotMeta meta) {
        if (meta.ffRun() == 0) {
            return null;
        }
        if (meta.ffRun() == 32) {
            return "int*";
        }
        // First non-0xFF byte must have its high bit set
        if ((meta.word()[meta.ffRun()] & 0x80) != 0) {
            return intFloor((32 - meta.ffRun()) * 8);
        }
        return null;
    }

    /**
     * Returns the tightest {@code intN+} pattern covering all {@code int} types from
     * {@code minBits} to 256. Returns {@code "int*"} when {@code minBits ≤ 8}.
     */
    private static String intFloor(int minBits) {
        if (minBits <= 8) {
            return "int*";
        }
        if (minBits >= 256) {
            return "int256";
        }
        return "int" + minBits + "+";
    }
}
