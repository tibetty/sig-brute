package me.tibetty.sigbrute.decode.infer;

import java.util.List;

/**
 * Infers ABI-type candidates for an all-zero 32-byte head slot.
 *
 * <p>
 * Every numeric type encodes the value zero as 32 zero bytes, so the candidate list is maximally
 * broad. {@code ufixed*} and {@code fixed*} are included because "any type is plausible" is the
 * intent for a zero slot; the user is expected to prune before running sig-brute (each wildcard
 * expands to 2 560 concrete types).
 */
final class ZeroSlotInferrer implements SlotInferrer {

    @Override
    public List<String> infer(SlotMeta meta) {
        if (!meta.isAllZero()) {
            return List.of();
        }
        return List.of("uint*", "int*", "ufixed*", "fixed*", "address", "bytes32", "bool");
    }
}
