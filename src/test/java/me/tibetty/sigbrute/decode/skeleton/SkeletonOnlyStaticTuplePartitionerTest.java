package me.tibetty.sigbrute.decode.skeleton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SkeletonOnlyStaticTuplePartitioner} (no local corpus required). */
class SkeletonOnlyStaticTuplePartitionerTest {

    @Test
    void refine_assignsSmallTupleThenLargeFixedArraySpan() {
        // Two consecutive opaque tuple head args: 2-slot inner tuple + 67-slot bytes32[67].
        var layoutHint = List.of(SkeletonTypes.TUPLE, SkeletonTypes.TUPLE);
        var minSlots = new int[]{1, 1};
        var tupleSlots = new int[]{1, 1};
        var body = new byte[69 * 32];
        // First tuple: two plausible static words (no interior offsets).
        body[0 * 32 + 31] = 1;
        body[1 * 32 + 31] = 2;
        // Second tuple: 67 static words without offset-like values in interior slots.
        for (var i = 2; i < 69; i++) {
            body[i * 32 + 31] = (byte) (i + 1);
        }

        var head = SkeletonLayout.HeadSection.of(body);
        SkeletonOnlyStaticTuplePartitioner.refine(body, layoutHint, minSlots, tupleSlots, head);

        assertEquals(2, tupleSlots[0]);
        assertEquals(67, tupleSlots[1]);
    }
}
