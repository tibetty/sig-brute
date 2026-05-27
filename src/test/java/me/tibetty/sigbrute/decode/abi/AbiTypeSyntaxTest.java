package me.tibetty.sigbrute.decode.abi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AbiTypeSyntaxTest {

    @Test
    void splitOutermostArraySuffix_inlineTupleDynamicArray() {
        var type = "(uint256,uint256,bool,bool,uint256,uint8,(string,(string,uint8)[],bytes4[],bytes[]))[]";
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(type);
        assertNotNull(parts);
        assertEquals(
            "(uint256,uint256,bool,bool,uint256,uint8,(string,(string,uint8)[],bytes4[],bytes[]))",
            parts.base());
        assertEquals("[]", parts.suffix());
    }

    @Test
    void splitOutermostArraySuffix_simpleDynamicArray() {
        var parts = AbiTypeSyntax.splitOutermostArraySuffix("bytes[]");
        assertNotNull(parts);
        assertEquals("bytes", parts.base());
        assertEquals("[]", parts.suffix());
    }

    @Test
    void splitArraySuffix_stillWorksForSimpleFixedArray() {
        var parts = AbiTypeSyntax.splitArraySuffix("bytes32[67]");
        assertNotNull(parts);
        assertEquals("bytes32", parts.base());
        assertEquals("[67]", parts.suffix());
    }

    @Test
    void splitOutermostArraySuffix_noArrayReturnsNull() {
        assertNull(AbiTypeSyntax.splitOutermostArraySuffix("uint256"));
    }

    @Test
    void peelOutermostArrayDimension_oneLevelFromDoubleArray() {
        var parts = AbiTypeSyntax.peelOutermostArrayDimension("(uint8,address)[][]");
        assertNotNull(parts);
        assertEquals("(uint8,address)[]", parts.base());
        assertEquals("[]", parts.suffix());
    }

    @Test
    void splitOutermostArraySuffix_peelsAllDimensionsAtOnce() {
        var parts = AbiTypeSyntax.splitOutermostArraySuffix("(uint8,address)[][]");
        assertNotNull(parts);
        assertEquals("(uint8,address)", parts.base());
        assertEquals("[][]", parts.suffix());
    }
}
