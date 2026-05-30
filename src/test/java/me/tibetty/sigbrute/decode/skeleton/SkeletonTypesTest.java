package me.tibetty.sigbrute.decode.skeleton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SkeletonTypesTest {

    @Test
    void kind_inlineTupleDoubleArray_isInlineTupleArrayKind() {
        assertEquals(SkeletonTypeKind.INLINE_TUPLE_ARRAY,
            SkeletonTypes.kind("(uint8,address)[][]"));
    }

    @Test
    void kind_staticInlineTuple_isStatic() {
        assertEquals(SkeletonTypeKind.STATIC,
            SkeletonTypes.kind("(uint256,address)"));
    }

    @Test
    void kind_addressArray_isDynamicPrimArray() {
        assertEquals(SkeletonTypeKind.DYNAMIC_PRIM_ARRAY, SkeletonTypes.kind("address[]"));
    }

    @Test
    void kind_opaqueTupleArray_isOpaqueTupleArrayKind() {
        assertEquals(SkeletonTypeKind.OPAQUE_TUPLE_ARRAY, SkeletonTypes.kind("tuple[]"));
        assertEquals(SkeletonTypeKind.OPAQUE_TUPLE_ARRAY, SkeletonTypes.kind("tuple[][]"));
    }

    @Test
    void inlineTupleBase_stripsArraySuffixes() {
        assertEquals("(uint8,address)", SkeletonTypes.inlineTupleBase("(uint8,address)[][]"));
        assertNull(SkeletonTypes.inlineTupleBase("address[]"));
    }
}
