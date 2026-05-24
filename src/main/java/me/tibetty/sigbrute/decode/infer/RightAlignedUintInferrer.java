package me.tibetty.sigbrute.decode.infer;

import java.util.List;

/**
 * Infers ABI-type candidates for a right-aligned 32-byte head slot that does not match the
 * all-zero, value=1, left-aligned, or address-shaped patterns.
 *
 * <p>
 * Two sub-cases based on the number of leading zero bytes ({@code firstNonZero}):
 *
 * <ul>
 *   <li><b>Medium / small value</b> ({@code firstNonZero ≥ 13}): a plain {@code uintN} has its
 *       non-zero bytes as a single contiguous run ending at byte 31. If there is an interior zero
 *       gap between two non-zero clusters, the slot likely holds packed {@code bytes32} data (e.g.
 *       two {@code uint128} halves). {@code bytes32} is prepended when a gap is detected.
 *   <li><b>Large value</b> ({@code firstNonZero} in {@code [1..11]}): the value's minimum bit-width
 *       is already large (> 168 bits), so {@code bytes32} — which allows any 32-byte pattern — is
 *       also plausible. {@code bytes32} is appended after the uint-floor candidate.
 * </ul>
 *
 * <p>
 * The minimum uint bit-width is derived from {@link SlotMeta#minBits()}: any {@code uintM} with
 * {@code M < minBits} cannot produce this encoding for well-formed calldata.
 */
final class RightAlignedUintInferrer implements SlotInferrer {

    @Override
    public List<String> infer(SlotMeta meta) {
        // Only handles right-aligned slots that were not handled by prior inferrers.
        // (all-zero, value=1, left-aligned, and address-shaped are already excluded.)
        if (meta.isAllZero() || meta.isValueOne() || meta.isLeftAligned()
            || meta.isAddressShaped()) {
            return List.of();
        }

        var minBits = meta.minBits();
        var minUint = uintFloor(minBits);

        // firstNonZero >= 13: medium/small value — bytes32 only when packed gap detected
        if (meta.firstNonZero() >= 13) {
            return meta.hasGap() ? List.of("bytes32", minUint) : List.of(minUint);
        }

        // firstNonZero in [1..11]: large value — bytes32 is plausible regardless of gap
        return List.of(minUint, "bytes32");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Returns the tightest {@code uintN+} pattern covering all {@code uint} types from
     * {@code minBits} to 256. Returns {@code "uint*"} when {@code minBits ≤ 8} (no narrowing
     * possible).
     */
    private static String uintFloor(int minBits) {
        if (minBits <= 8) {
            return "uint*";
        }
        if (minBits >= 256) {
            return "uint256";
        }
        return "uint" + minBits + "+";
    }
}
