package me.tibetty.sigbrute.decode.infer;

import static me.tibetty.sigbrute.decode.abi.AbiTypeSyntax.ADDRESS;

import java.util.LinkedHashSet;
import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;

/**
 * Heuristic ABI-type inference for a single 32-byte head slot.
 *
 * <p>
 * This class is the coordinator: it pre-analyses the slot into a {@link SlotMeta} record once,
 * then delegates to each registered {@link SlotInferrer} in order. Each inferrer focuses on one
 * slot shape and returns an empty list when the shape does not apply. Results are merged via a
 * {@link LinkedHashSet} to preserve insertion order while deduplicating.
 *
 * <h3>Core rules (provably correct for well-formed ABI calldata)</h3>
 *
 * <ul>
 * <li><b>Left-aligned</b> (byte 0 non-zero): the slot is {@code bytesN}-shaped. {@code
 *       uint*}/address are right-aligned and cannot produce a non-zero high byte.
 * <li><b>Minimum bit-width</b>: if the slot has {@code k} leading zero bytes the value fits in
 * {@code (32−k)×8} bits. A well-formed {@code uintM} encoding with {@code M < (32−k)×8} is
 * impossible, so smaller types are excluded. The emitted pattern {@code uintN+} expands to
 * {@code uintN, uint(N+8), …, uint256}.
 * </ul>
 *
 * The heuristic is intentionally conservative — prefer narrowing candidate counts (small search
 * space) over completeness. Users can loosen the YAML by hand.
 *
 * <table border="1">
 * <tr>
 * <th>Slot shape</th>
 * <th>Candidates</th>
 * <th>Count</th>
 * </tr>
 * <tr>
 * <td>All zeros</td>
 * <td>uint*, int*, ufixed*, fixed*, address, bytes32, bool</td>
 * <td>5187</td>
 * </tr>
 * <tr>
 * <td>Value == 1</td>
 * <td>uint*, int*, ufixed*, fixed*, bool</td>
 * <td>5185</td>
 * </tr>
 * <tr>
 * <td>Left-aligned, all 0xFF</td>
 * <td>bytes32, uint256, int*</td>
 * <td>34</td>
 * </tr>
 * <tr>
 * <td>Left-aligned, full word with clean sign-ext</td>
 * <td>bytes32, uint256, intN+</td>
 * <td>3–33</td>
 * </tr>
 * <tr>
 * <td>Left-aligned, full word, no sign-ext</td>
 * <td>bytes32, uint256, int256</td>
 * <td>3</td>
 * </tr>
 * <tr>
 * <td>Left-aligned, trailing zeros + clean sign-ext</td>
 * <td>bytes(lastNZ+1), intN+</td>
 * <td>2–32</td>
 * </tr>
 * <tr>
 * <td>Left-aligned, trailing zeros, no sign-ext (N≠24)</td>
 * <td>bytes(lastNZ+1)</td>
 * <td>1</td>
 * </tr>
 * <tr>
 * <td>Left-aligned, trailing zeros, bytes24 shape</td>
 * <td>bytes24, function</td>
 * <td>2</td>
 * </tr>
 * <tr>
 * <td>Address-shaped, high-entropy (firstNZ==12)</td>
 * <td>address, uint160+</td>
 * <td>14</td>
 * </tr>
 * <tr>
 * <td>Address-shaped, low-entropy (firstNZ==12)</td>
 * <td>address, uint160+</td>
 * <td>14</td>
 * </tr>
 * <tr>
 * <td>Right-aligned, firstNZ≥13, packed</td>
 * <td>bytes32, uint(minBits)+</td>
 * <td>2–26</td>
 * </tr>
 * <tr>
 * <td>Right-aligned, firstNZ≥13, contiguous</td>
 * <td>uint(minBits)+</td>
 * <td>1–25</td>
 * </tr>
 * <tr>
 * <td>Large right-aligned (firstNZ 1–11)</td>
 * <td>uint(minBits)+, bytes32</td>
 * <td>3–13</td>
 * </tr>
 * </table>
 */
public final class TypeInferrer {

    private static final List<SlotInferrer> INFERRERS = List.of(
        new ZeroSlotInferrer(),
        new ValueOneInferrer(),
        new LeftAlignedInferrer(),
        new AddressInferrer(),
        new RightAlignedUintInferrer()
    );

    private TypeInferrer() {
    }

    /**
     * Candidate ABI types for a single 32-byte head slot.
     *
     * <p>
     * Delegates to each registered {@link SlotInferrer} in order. Shapes are mutually exclusive so
     * at most one inferrer returns a non-empty list; the {@link LinkedHashSet} deduplicates as free
     * insurance against future overlap.
     */
    public static List<String> inferStatic(byte[] word) {
        if (word.length != 32) {
            throw new IllegalArgumentException("word must be 32 bytes");
        }
        var meta = SlotMeta.of(word);
        var result = new LinkedHashSet<String>();
        for (var inferrer : INFERRERS) {
            result.addAll(inferrer.infer(meta));
        }
        return List.copyOf(result);
    }

    /** Candidate base-type patterns for an element of a primitive (one-slot) array. */
    public static List<String> inferArrayElement(byte[] word) {
        return inferStatic(word);
    }

    /** Renders a short comment fragment summarising a head slot's value. */
    public static String shapeLabel(byte[] word) {
        var meta = SlotMeta.of(word);

        if (meta.isAllZero()) {
            return "zero";
        }

        if (meta.isLeftAligned()) {
            if (meta.isFullWord()) {
                return "full word";
            }
            return "bytes" + (meta.lastNonZero() + 1) + "-shaped";
        }

        if (meta.isAddressShaped()) {
            return meta.highEntropy() ? ADDRESS : "address-or-uint160";
        }

        if (meta.firstNonZero() >= 24) {
            return "small uint";
        }

        if (meta.firstNonZero() >= 12) {
            return "medium uint";
        }

        return "large uint or bytes32";
    }

    /** Full hex of a 32-byte word, "0x"-prefixed. */
    public static String hexOf(byte[] word) {
        return "0x" + HexUtil.toHex(word);
    }
}
