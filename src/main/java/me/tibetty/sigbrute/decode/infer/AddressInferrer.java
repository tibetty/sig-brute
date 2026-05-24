package me.tibetty.sigbrute.decode.infer;

import java.util.List;

/**
 * Infers ABI-type candidates for an address-shaped 32-byte head slot (exactly 12 leading zero
 * bytes, i.e. {@code firstNonZero == 12}).
 *
 * <p>
 * The canonical {@code address} type encodes as 20 data bytes right-aligned in a 32-byte word
 * (12 leading zeros). A {@code uint160} value or any {@code uint} type wider than 160 bits can
 * produce the same layout when its numeric value fits in 160 bits.
 *
 * <p>
 * Entropy heuristic — distinguishes real Keccak-256 derived addresses from small-integer casts:
 * <ul>
 *   <li>Real address: ~19.9 / 20 data bytes are non-zero on average.
 *   <li>Numeric cast: a value small enough to be a meaningful parameter has many leading zeros
 *       within the 20-byte body (e.g. a timestamp cast: ~3 non-zero bytes).
 * </ul>
 * Both cases emit the same candidates ({@code address, uint160+}) because even a low-entropy
 * value may be an address cast to {@code uint160} in the contract. The entropy field is retained
 * in {@link SlotMeta} for informational use in {@link TypeInferrer#shapeLabel}.
 *
 * <p>
 * Gap heuristic — if a packed {@code bytes32} pattern is detected (non-zero bytes in two
 * separated clusters), {@code bytes32} is prepended to the candidate list.
 */
final class AddressInferrer implements SlotInferrer {

    @Override
    public List<String> infer(SlotMeta meta) {
        if (!meta.isAddressShaped()) {
            return List.of();
        }
        // Any uintN for N ≥ 160 is a valid encoding when firstNonZero == 12.
        // uint160+ expands to uint160, uint168, …, uint256 (25 types) plus address.
        if (meta.hasGap()) {
            return List.of("bytes32", "address", "uint160+");
        }
        return List.of("address", "uint160+");
    }
}
