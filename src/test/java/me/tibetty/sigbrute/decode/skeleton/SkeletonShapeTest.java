package me.tibetty.sigbrute.decode.skeleton;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.tibetty.sigbrute.decode.DecodedArg;
import org.junit.jupiter.api.Test;

class SkeletonShapeTest {

    @Test
    void shapeFromHint_nestedPrimArray() {
        var shape = SkeletonShape.shapeFromHint("bytes32[][]");
        assertInstanceOf(DecodedArg.PrimArray.class, shape);
        assertTrue(((DecodedArg.PrimArray) shape).arraySuffix().equals("[][]"));
    }

    @Test
    void shapeFromHint_inlineTupleArrayElementShape() {
        var shape = SkeletonShape.shapeFromHint("(address,uint256)[]");
        assertInstanceOf(DecodedArg.Tuple.class, shape);
        var ta = (DecodedArg.Tuple) shape;
        assertTrue(ta.arraySuffix().contains("[]"));
        assertTrue(ta.fields().size() >= 2);
    }
}
