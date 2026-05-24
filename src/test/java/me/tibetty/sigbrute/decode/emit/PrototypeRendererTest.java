package me.tibetty.sigbrute.decode.emit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;
import org.junit.jupiter.api.Test;

class PrototypeRendererTest {

    @Test
    void renderLeafWithConcreteType() {
        assertEquals("transfer(address)", PrototypeRenderer.render("transfer",
            List.of(new DecodedArg.Leaf(List.of("address"), null))));
    }

    @Test
    void renderLeafWildcardUintExpands() {
        assertEquals("<fn>(uint256)",
            PrototypeRenderer.render(null, List.of(new DecodedArg.Leaf(List.of("uint*"), null))));
    }

    @Test
    void renderLeafWildcardIntExpands() {
        assertEquals("<fn>(int256)",
            PrototypeRenderer.render(null, List.of(new DecodedArg.Leaf(List.of("int*"), null))));
    }

    @Test
    void renderLeafWildcardBytesExpands() {
        assertEquals("<fn>(bytes32)",
            PrototypeRenderer.render(null, List.of(new DecodedArg.Leaf(List.of("bytes*"), null))));
    }

    @Test
    void renderLeafWildcardFixedExpands() {
        assertEquals("<fn>(fixed128x18)",
            PrototypeRenderer.render(null, List.of(new DecodedArg.Leaf(List.of("fixed*"), null))));
    }

    @Test
    void renderLeafWildcardUfixedExpands() {
        assertEquals("<fn>(ufixed128x18)",
            PrototypeRenderer.render(null, List.of(new DecodedArg.Leaf(List.of("ufixed*"), null))));
    }

    @Test
    void renderLeafEmptyCandidatesFallsBackToUint256() {
        assertEquals("<fn>(uint256)",
            PrototypeRenderer.render(null, List.of(new DecodedArg.Leaf(List.of(), null))));
    }

    @Test
    void renderPrimArrayDynamic() {
        assertEquals("<fn>(uint256[])", PrototypeRenderer.render(null,
            List.of(new DecodedArg.PrimArray("[]", List.of("uint256"), null))));
    }

    @Test
    void renderPrimArrayFixed() {
        assertEquals("<fn>(address[4])", PrototypeRenderer.render(null,
            List.of(new DecodedArg.PrimArray("[4]", List.of("address"), null))));
    }

    @Test
    void renderTuplePlain() {
        assertEquals("<fn>((address,uint256))",
            PrototypeRenderer.render(null,
                List.of(
                    new DecodedArg.Tuple("", List.of(new DecodedArg.Leaf(List.of("address"), null),
                        new DecodedArg.Leaf(List.of("uint256"), null)), null))));
    }

    @Test
    void renderTupleArray() {
        assertEquals("<fn>((address,uint256)[])", PrototypeRenderer.render(null,
            List.of(
                new DecodedArg.Tuple("[]", List.of(new DecodedArg.Leaf(List.of("address"), null),
                    new DecodedArg.Leaf(List.of("uint256"), null)), null))));
    }

    @Test
    void renderMultipleTopLevelArgs() {
        assertEquals("foo(bool,address)",
            PrototypeRenderer.render("foo", List.of(new DecodedArg.Leaf(List.of("bool"), null),
                new DecodedArg.Leaf(List.of("address"), null))));
    }

    @Test
    void renderNullMethodNameUsesPlaceholder() {
        assertEquals("<fn>(uint256)",
            PrototypeRenderer.render(null, List.of(new DecodedArg.Leaf(List.of("uint256"), null))));
    }

    @Test
    void renderBlankMethodNameUsesPlaceholder() {
        assertEquals("<fn>(uint256)",
            PrototypeRenderer.render("", List.of(new DecodedArg.Leaf(List.of("uint256"), null))));
    }
}
