package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeneralizedTypeInferrerTest {

    @Test
    void zeroSlotCollapsesNumericFamiliesToWildcards() {
        var word = new byte[32];
        var types = GeneralizedTypeInferrer.inferStatic(word);
        assertTrue(types.contains("uint*"));
        assertTrue(types.contains("int*"));
        assertTrue(types.contains("fixed*"));
        assertTrue(types.contains("ufixed*"));
        assertTrue(types.contains("address"));
        assertTrue(types.contains("bytes32"));
        assertTrue(types.contains("bool"));
        assertFalse(types.stream().anyMatch(t -> t.matches("uint\\d+$")));
    }

    @Test
    void smallRightAlignedValueUsesUintFloor() {
        var word = new byte[32];
        word[31] = (byte) 0xe8;
        word[30] = 0x03;
        var types = GeneralizedTypeInferrer.inferStatic(word);
        assertEquals(List.of("uint16+"), types);
    }

    @Test
    void leftAlignedBytesUsesBytesLengthFloor() {
        var word = new byte[32];
        word[0] = 0x01;
        word[1] = 0x02;
        var types = GeneralizedTypeInferrer.inferStatic(word);
        assertTrue(types.contains("bytes2+"));
        assertFalse(types.contains("bytes2"));
        assertFalse(types.stream().anyMatch(t -> t.startsWith("uint")));
    }

    @Test
    void fullWordLeftAlignedCollapsesUintAndInt() {
        var word = new byte[32];
        for (var i = 0; i < 32; i++) {
            word[i] = (byte) 0xFF;
        }
        var types = GeneralizedTypeInferrer.inferStatic(word);
        assertTrue(types.contains("bytes32"));
        assertTrue(types.contains("int*"));
        assertFalse(types.contains("int256"));
        assertTrue(types.contains("uint*") || types.contains("uint256"));
    }

    @Test
    void valueOneSlotUsesNumericWildcards() {
        var word = new byte[32];
        word[31] = 1;
        var types = GeneralizedTypeInferrer.inferStatic(word);
        assertTrue(types.contains("uint*"));
        assertTrue(types.contains("int*"));
        assertTrue(types.contains("fixed*"));
        assertTrue(types.contains("ufixed*"));
        assertTrue(types.contains("bool"));
    }

    @Test
    void patternsGeneralizeDirectly() {
        var raw = List.of("uint8", "uint16", "uint256", "bytes4", "bytes8", "int32", "int256",
            "fixed128x18", "fixed256x18", "ufixed*");
        var out = GeneralizedTypePatterns.generalize(raw);
        assertTrue(out.contains("uint8+"));
        assertTrue(out.contains("bytes4+"));
        assertTrue(out.contains("int32+"));
        assertTrue(out.contains("fixed*x18"));
        assertTrue(out.contains("ufixed*"));
    }
}
