package me.tibetty.sigbrute.decode.abi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;
import org.junit.jupiter.api.Test;

class ArraySuffixComposerTest {

    @Test
    void mergeSuffix_prefersLongerOuterSuffix() {
        assertEquals("[][]", ArraySuffixComposer.mergeSuffix("[]", "[][]"));
    }

    @Test
    void mergeSuffix_equalDimensionsConcatenate() {
        assertEquals("[][]", ArraySuffixComposer.mergeSuffix("[]", "[]"));
    }

    @Test
    void attach_tupleDoubleArray() {
        var inner = new DecodedArg.Tuple("[]",
            List.of(new DecodedArg.Leaf(List.of("uint8"), ""),
                new DecodedArg.Leaf(List.of("address"), "")),
            "inline tuple[]");
        var wrapped = ArraySuffixComposer.attach(inner, "[][]");
        assertInstanceOf(DecodedArg.Tuple.class, wrapped);
        assertEquals("[][]", ((DecodedArg.Tuple) wrapped).arraySuffix());
    }

    @Test
    void attach_primArrayConcatenates() {
        var inner = new DecodedArg.PrimArray("[]", List.of("bytes32"), "1 element");
        var wrapped = ArraySuffixComposer.attach(inner, "[]");
        assertInstanceOf(DecodedArg.PrimArray.class, wrapped);
        assertEquals("[][]", ((DecodedArg.PrimArray) wrapped).arraySuffix());
    }
}
