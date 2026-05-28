package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShallowSkeletonHintsTest {

    @Test
    void abstractTopLevelType_collapsesInlineTupleAndArray() {
        assertEquals("tuple", ShallowSkeletonHints.abstractTopLevelType("(uint256,address)"));
        assertEquals("tuple[]", ShallowSkeletonHints.abstractTopLevelType("(address,uint256,address)[]"));
        assertEquals("tuple[2]", ShallowSkeletonHints.abstractTopLevelType("(uint64,uint64,address)[2]"));
    }

    @Test
    void abstractTopLevelType_keepsScalarsAndOpaqueTuple() {
        assertEquals("address", ShallowSkeletonHints.abstractTopLevelType("address"));
        assertEquals("bytes", ShallowSkeletonHints.abstractTopLevelType("bytes"));
        assertEquals("uint256", ShallowSkeletonHints.abstractTopLevelType("uint256"));
        assertEquals("tuple", ShallowSkeletonHints.abstractTopLevelType("tuple"));
        assertEquals("tuple[]", ShallowSkeletonHints.abstractTopLevelType("tuple[]"));
    }

    @Test
    void abstractTopLevelTypes_nullSafe() {
        assertEquals(List.of(), ShallowSkeletonHints.abstractTopLevelTypes(null));
    }

    @Test
    void layoutHints_usesInlineTupleHeadSlots() {
        var shallow = List.of("tuple", "address");
        var inline = List.of("(uint256,address)", "address");
        assertEquals(inline, ShallowSkeletonHints.layoutHints(shallow, inline));
    }

    @Test
    void dynamicDecodeType_usesInlineTupleAndTupleArrayShape() {
        var inline = List.of("(address[],bytes[],uint256[])", "(uint256,address)[]");
        assertEquals("(address[],bytes[],uint256[])",
            ShallowSkeletonHints.dynamicDecodeType(inline, 0, "tuple"));
        assertEquals("(uint256,address)[]",
            ShallowSkeletonHints.dynamicDecodeType(inline, 1, "tuple[]"));
    }

    @Test
    void widenOpaqueRegionForEmit_reinfersFromCommentHex() {
        var leaf = new me.tibetty.sigbrute.decode.DecodedArg.Leaf(List.of("uint256"),
            "small uint 0x0000000000000000000000000000000000000000000000000000000000000802");
        var widened = (me.tibetty.sigbrute.decode.DecodedArg.Leaf) ShallowSkeletonHints
            .widenOpaqueRegionForEmit(leaf);
        assertTrue(widened.candidates().contains("uint16+"));
        assertEquals(List.of("uint256"), leaf.candidates());
    }

    @Test
    void singletonFixedArrayField_detectsParenthesizedFixedArray() {
        assertEquals("bytes32[67]",
            ShallowSkeletonHints.singletonFixedArrayField(List.of("bytes32[67]")));
        assertNull(ShallowSkeletonHints.singletonFixedArrayField(List.of("bytes32", "bytes32")));
        assertNull(ShallowSkeletonHints.singletonFixedArrayField(List.of()));
    }

    @Test
    void opaqueTupleFieldHints_fromInlineTopLevelTuple() {
        var inline = List.of("(address[],bytes32[][])");
        assertEquals(2, ShallowSkeletonHints.opaqueTupleFieldHints(inline, 0).size());
    }

    @Test
    void withShallowSkeleton_swapMultiSignature() {
        var types = List.of(
            "(address,uint256,address)[]",
            "(address,uint256,uint256,address)[]",
            "bytes",
            "address",
            "(uint64,uint64,address)");
        assertEquals(
            List.of("tuple[]", "tuple[]", "bytes", "address", "tuple"),
            ShallowSkeletonHints.abstractTopLevelTypes(types));
    }
}
