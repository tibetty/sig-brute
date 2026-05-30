package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SignatureStructureTest {

    @Test
    void parseSignature_nestedTupleAndArrays() {
        var known = SignatureStructure.parseSignature(
            "swap((uint8,address)[],address,uint256)");
        var decoded = SignatureStructure.fromDecodedArgs(List.of(
            new DecodedArg.Tuple("[]",
                List.of(new DecodedArg.Leaf(List.of("uint8"), null),
                    new DecodedArg.Leaf(List.of("address"), null)),
                null),
            new DecodedArg.Leaf(List.of("address"), null),
            new DecodedArg.Leaf(List.of("uint256"), null)));
        assertTrue(known.structureEquals(decoded));
    }

    @Test
    void structureDiffersWhenTupleFlattenedToLeaves() {
        var known = SignatureStructure.parseSignature("forwardEth(bytes,(uint256,address))");
        var flat = SignatureStructure.fromDecodedArgs(List.of(
            new DecodedArg.Leaf(List.of("bytes"), null),
            new DecodedArg.Leaf(List.of("uint256"), null),
            new DecodedArg.Leaf(List.of("address"), null)));
        assertFalse(known.structureEquals(flat));
    }

    @Test
    void primArrayMatchesLeafWithArraySuffix() {
        var known = SignatureStructure.parseSignature("tokens(address[])");
        var decoded = SignatureStructure.fromDecodedArgs(
            List.of(new DecodedArg.PrimArray("[]", List.of("address"), null)));
        assertTrue(known.structureEquals(decoded));
    }

    @Test
    void topLevelShapeEquals_ignoresTupleInterior() {
        var known = SignatureStructure.parseSignature("sendL2Message((bytes32,uint256),bytes32,bytes32)");
        var wrongInterior = List.<DecodedArg>of(
            new DecodedArg.Tuple("",
                List.of(new DecodedArg.Leaf(List.of("uint8"), null),
                    new DecodedArg.Leaf(List.of("bool"), null),
                    new DecodedArg.Leaf(List.of("address"), null)),
                null),
            new DecodedArg.Leaf(List.of("bytes32"), null),
            new DecodedArg.Leaf(List.of("bytes32"), null));
        assertFalse(known.structureEquals(SignatureStructure.fromDecodedArgs(wrongInterior)));
        assertTrue(known.topLevelShapeEquals(
            SignatureStructure.topLevelShapeFromDecodedArgs(wrongInterior)));
    }

    @Test
    void topLevelShapeFromSignature_collapsesInlineTuple() {
        var known = SignatureStructure.topLevelShapeFromSignature(
            "sendL2Message((bytes32,uint256),bytes32,bytes32)");
        var decoded = SignatureStructure.topLevelShapeFromDecodedArgs(List.of(
            new DecodedArg.Tuple("", List.of(), null),
            new DecodedArg.Leaf(List.of("bytes32"), null),
            new DecodedArg.Leaf(List.of("bytes32"), null)));
        assertTrue(known.topLevelShapeEquals(decoded));
    }

    @Test
    void topLevelShapeDiffersWhenTopLevelArityWrong() {
        var known = SignatureStructure.topLevelShapeFromSignature("foo(tuple,bytes32)");
        var decoded = SignatureStructure.topLevelShapeFromDecodedArgs(List.of(
            new DecodedArg.Tuple("", List.of(), null)));
        assertFalse(known.topLevelShapeEquals(decoded));
    }
}
