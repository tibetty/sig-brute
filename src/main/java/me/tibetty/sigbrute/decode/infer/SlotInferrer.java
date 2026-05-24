package me.tibetty.sigbrute.decode.infer;

import java.util.List;

/**
 * Strategy for inferring ABI-type candidates from a pre-analysed 32-byte head slot.
 *
 * <p>
 * Each implementation focuses on one slot shape (all-zero, left-aligned, address-shaped, …).
 * When the slot does not match the implementor's shape, an empty list is returned.
 *
 * <p>
 * The coordinator ({@link TypeInferrer}) calls every registered {@code SlotInferrer} in order and
 * merges their results into a single deduplicated list.
 */
@FunctionalInterface
interface SlotInferrer {

    /**
     * Returns the candidate ABI-type patterns for {@code meta}, or an empty list if this inferrer
     * does not apply to the slot's shape.
     */
    List<String> infer(SlotMeta meta);
}
