package me.tibetty.sigbrute.decode.infer;

import java.util.List;

/**
 * Infers ABI-type candidates for a slot whose value is exactly 1 (byte 31 == {@code 0x01}, all
 * other bytes zero).
 *
 * <p>
 * {@code bool(true)}, {@code uint8(1)}, {@code int8(1)}, {@code ufixed8x1} (i.e. 1e-N), and
 * {@code fixed8x1} all encode identically. The full {@code uint*}, {@code int*}, {@code ufixed*},
 * and {@code fixed*} ranges are included along with {@code bool}. The user is expected to prune
 * before running sig-brute; {@code ufixed*} / {@code fixed*} each expand to 2 560 concrete types.
 */
final class ValueOneInferrer implements SlotInferrer {

    @Override
    public List<String> infer(SlotMeta meta) {
        if (!meta.isValueOne()) {
            return List.of();
        }
        return List.of("uint*", "int*", "ufixed*", "fixed*", "bool");
    }
}
