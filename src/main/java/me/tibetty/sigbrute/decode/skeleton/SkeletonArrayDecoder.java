package me.tibetty.sigbrute.decode.skeleton;

import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.abi.ArraySuffixComposer;
import me.tibetty.sigbrute.decode.DecodedArg;
import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.decode.layout.OffsetTable;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;
import me.tibetty.sigbrute.decode.strategy.body.GreedyBodyDecoder;

/** Skeleton-guided decode of dynamic arrays and dynamic field tails. */
public final class SkeletonArrayDecoder {

    private static final String BYTES = AbiTypeSyntax.BYTES;
    private static final String STRING = AbiTypeSyntax.STRING;
    private static final String ELEMENT_COUNT_NOTE_SUFFIX = " element(s)";

    private SkeletonArrayDecoder() {
    }

    static DecodedArg decodeTopLevel(DecodeContext ctx, String type, byte[] tail) {
        if (AbiTypeSyntax.isDynamicBytesOrString(type)) {
            return decodeDynamicBytesOrString(type, tail);
        }

        if (type.indexOf('[') >= 0) {
            return decodeDynamicArrayField(ctx, tail, type);
        }

        return ctx.decodeDynamic(tail);
    }

    static DecodedArg decodeDynamicFieldValue(DecodeContext ctx, String hint, byte[] tail) {
        if (AbiTypeSyntax.isDynamicBytesOrString(hint)) {
            return decodeDynamicBytesOrString(hint, tail);
        }
        if (SkeletonTypes.isInlineTuple(hint)) {
            return new DecodedArg.Tuple("",
                SkeletonInlineTupleDecoder.decodeWithFieldHints(ctx, tail,
                    SkeletonTypes.inlineFieldTypes(hint)),
                "dynamic inline tuple body");
        }
        if (hint.indexOf('[') >= 0) {
            var parts = AbiTypeSyntax.splitOutermostArraySuffix(hint);
            if (parts != null && AbiTypeSyntax.isDynamicBytesOrString(parts.base())
                && !looksLikeDynamicArrayCount(tail)) {
                return new DecodedArg.PrimArray(parts.suffix(), List.of(parts.base()),
                    "dynamic " + hint + " (skeleton; payload not decoded)");
            }
            return decodeDynamicArrayField(ctx, tail, hint);
        }
        return ctx.decodeDynamic(tail);
    }

    /**
     * Decodes one dynamic array dimension at a time. Nested {@code T[][]} uses an offset table per
     * outer dimension; inline tuple elements attach their dimension via {@link ArraySuffixComposer}.
     */
    static DecodedArg decodeDynamicArrayField(DecodeContext ctx, byte[] tail, String arrayType) {
        var flatEarly = tryFlatMultiDimDecode(ctx, tail, arrayType);
        if (flatEarly != null) {
            return flatEarly;
        }

        var peel = AbiTypeSyntax.peelOutermostArrayDimension(arrayType);
        if (peel == null) {
            return ctx.decodeDynamic(tail);
        }
        var elemType = peel.base();
        var dimension = peel.suffix();

        if (!looksLikeDynamicArrayCount(tail)) {
            return badCountFallback(ctx, tail, arrayType, elemType, dimension);
        }

        var count = AbiCodec.dynamicArrayCount(tail);
        if (count == 0) {
            return emptyArray(ctx, tail, elemType, dimension);
        }

        return decodeCountedArrayElement(ctx, tail, arrayType, elemType, dimension, count);
    }

    private static DecodedArg tryFlatMultiDimDecode(DecodeContext ctx, byte[] tail, String arrayType) {
        var view = AbiTypeView.of(arrayType);
        if (view.dynamicDimensionCount() < 2 || !looksLikeDynamicArrayCount(tail)) {
            return null;
        }
        // Opaque tuple[][]… needs per-dimension offset tables; single-blob tuple[] decode is wrong.
        if (SkeletonTypes.TUPLE.equals(view.base())) {
            return null;
        }
        return decodeFlatDynamicArray(ctx, tail, arrayType);
    }

    private static DecodedArg decodeCountedArrayElement(DecodeContext ctx, byte[] tail, String arrayType,
        String elemType, String dimension, int count) {
        if (hasAnotherArrayDimension(elemType)) {
            return decodeNestedArrayDimension(ctx, tail, arrayType, elemType, dimension, count);
        }
        return decodeSingleDimensionElement(ctx, tail, elemType, dimension, count);
    }

    private static DecodedArg decodeNestedArrayDimension(DecodeContext ctx, byte[] tail, String arrayType,
        String elemType, String dimension, int count) {
        var decoded = decodeOffsetIndexedElements(ctx, tail, count, elemType);
        if (decoded != null) {
            return ArraySuffixComposer.attach(decoded, dimension);
        }
        var flat = decodeFlatDynamicArray(ctx, tail, arrayType);
        if (flat != null) {
            return flat;
        }
        return SkeletonShape.skeletonArrayFallback(arrayType);
    }

    private static DecodedArg decodeSingleDimensionElement(DecodeContext ctx, byte[] tail, String elemType,
        String dimension, int count) {
        if (SkeletonTypes.isInlineTuple(elemType)) {
            var decoded = decodeDynamicInlineTupleArray(ctx, tail,
                SkeletonTypes.inlineFieldTypes(elemType));
            return ArraySuffixComposer.attach(decoded, dimension);
        }
        if (SkeletonTypes.TUPLE.equals(elemType)) {
            var decoded = decodeOpaqueTupleArray(ctx, tail, count);
            return ArraySuffixComposer.attach(decoded, dimension);
        }
        if (SkeletonTypes.isTupleHint(elemType)) {
            var decoded = decodeDynamicTupleArray(ctx, tail);
            return ArraySuffixComposer.attach(decoded, dimension);
        }
        if (AbiTypeSyntax.isStaticPrimitive(elemType) || AbiTypeSyntax.isBytesN(elemType)
            || AbiTypeSyntax.isDynamicBytesOrString(elemType)) {
            return countedPrimArray(dimension, elemType, count);
        }
        return ctx.decodeDynamic(tail);
    }

    private static DecodedArg badCountFallback(DecodeContext ctx, byte[] tail, String arrayType, String elemType,
        String dimension) {
        if (SkeletonTypes.isInlineTuple(elemType)) {
            return new DecodedArg.Tuple(
                ArraySuffixComposer.mergeSuffix("", dimension),
                SkeletonShape.shapeFieldsFromHints(SkeletonTypes.inlineFieldTypes(elemType)),
                "inline tuple[] (skeleton shape; bad count word)");
        }
        if (SkeletonTypes.TUPLE.equals(elemType)) {
            var heuristic = ctx.decodeBody(AbiCodec.slice(tail, 32, Math.max(0, tail.length - 32)));
            return new DecodedArg.Tuple(
                ArraySuffixComposer.mergeSuffix("", dimension),
                heuristic,
                "opaque tuple[] (heuristic; bad count word)");
        }
        var fallback = SkeletonShape.skeletonArrayFallback(arrayType);
        if (fallback instanceof DecodedArg.PrimArray) {
            return fallback;
        }
        return ctx.decodeDynamic(tail);
    }

    private static DecodedArg emptyArray(DecodeContext ctx, byte[] tail, String elemType,
        String dimension) {
        if (SkeletonTypes.isInlineTuple(elemType)) {
            return new DecodedArg.Tuple(dimension,
                SkeletonShape.shapeFieldsFromHints(SkeletonTypes.inlineFieldTypes(elemType)),
                "empty inline tuple[]");
        }
        if (SkeletonTypes.TUPLE.equals(elemType)) {
            return new DecodedArg.Tuple(dimension, opaqueTupleElementShape(ctx, tail),
                "empty opaque tuple[]");
        }
        if (SkeletonTypes.isTupleHint(elemType)) {
            return new DecodedArg.Tuple(dimension, List.of(), "empty tuple[]");
        }
        return new DecodedArg.PrimArray(dimension, List.of(elemType), "empty array");
    }

    private static DecodedArg decodeOffsetIndexedElements(DecodeContext ctx, byte[] tail, int count, String elemType) {
        var remaining = tail.length - 32;
        var offsets = OffsetTable.parseMonotonic(tail, count, remaining);
        if (offsets.length != count) {
            return null;
        }
        DecodedArg shape = null;
        for (var i = 0; i < count; i++) {
            var from = 32 + offsets[i];
            var to = (i + 1 < count) ? 32 + offsets[i + 1] : tail.length;
            var elem = decodeDynamicArrayField(ctx, AbiCodec.slice(tail, from, to - from), elemType);
            if (shape == null) {
                shape = elem;
            }
        }
        return shape;
    }

    /**
     * Decodes a dynamic array when all dimensions are present in one tail blob (no per-row offset
     * table). Used when nested offset-indexed decoding fails but the count word is valid.
     */
    private static DecodedArg decodeFlatDynamicArray(DecodeContext ctx, byte[] tail, String arrayType) {
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(arrayType);
        if (parts == null) {
            return null;
        }
        var elemType = parts.base();
        var suffix = parts.suffix();
        if (SkeletonTypes.isInlineTuple(elemType)) {
            var decoded = decodeDynamicInlineTupleArray(ctx, tail,
                SkeletonTypes.inlineFieldTypes(elemType));
            return ArraySuffixComposer.attach(decoded, suffix);
        }
        if (SkeletonTypes.TUPLE.equals(elemType)) {
            if (!looksLikeDynamicArrayCount(tail)) {
                return null;
            }
            var count = AbiCodec.dynamicArrayCount(tail);
            var decoded = decodeOpaqueTupleArray(ctx, tail, count);
            return ArraySuffixComposer.attach(decoded, suffix);
        }
        if (SkeletonTypes.isTupleHint(elemType)) {
            var decoded = decodeDynamicTupleArray(ctx, tail);
            return ArraySuffixComposer.attach(decoded, suffix);
        }
        if (AbiTypeSyntax.isStaticPrimitive(elemType) || AbiTypeSyntax.isBytesN(elemType)
            || AbiTypeSyntax.isDynamicBytesOrString(elemType)) {
            return countedPrimArray(suffix, elemType, AbiCodec.dynamicArrayCount(tail));
        }
        return null;
    }

    static DecodedArg decodeDynamicInlineTupleArray(DecodeContext ctx, byte[] tail,
        List<String> elementFieldHints) {
        if (!looksLikeDynamicArrayCount(tail)) {
            return new DecodedArg.Tuple("",
                SkeletonShape.shapeFieldsFromHints(elementFieldHints),
                "inline tuple[] (skeleton shape; bad count word)");
        }
        var count = AbiCodec.safeToInt(AbiCodec.uintOf(AbiCodec.slice(tail, 0, 32)),
            "inline tuple[] count");
        if (count == 0) {
            return new DecodedArg.Tuple("", SkeletonShape.shapeFieldsFromHints(elementFieldHints),
                "empty inline tuple[]");
        }

        var remaining = tail.length - 32;
        var elemOffsets = OffsetTable.parseMonotonic(tail, count, remaining);
        if (elemOffsets.length == count) {
            var perElement = new ArrayList<List<DecodedArg>>(count);
            for (var i = 0; i < count; i++) {
                var from = 32 + elemOffsets[i];
                var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : tail.length;
                perElement.add(SkeletonInlineTupleDecoder.decodeWithFieldHints(ctx,
                    AbiCodec.slice(tail, from, to - from), elementFieldHints));
            }
            return SkeletonShape.inlineTupleArrayElement(elementFieldHints, perElement.get(0),
                count + " elements; using element[0] — others may differ");
        }

        var staticSlots = SkeletonInlineTupleDecoder.staticSlotsForFieldHintList(elementFieldHints);
        if (staticSlots > 0 && remaining == count * staticSlots * 32) {
            var width = staticSlots * 32;
            var shape = SkeletonInlineTupleDecoder.decodeWithFieldHints(ctx,
                AbiCodec.slice(tail, 32, width), elementFieldHints);
            return SkeletonShape.inlineTupleArrayElement(elementFieldHints, shape,
                count + " elements × " + staticSlots + " slot(s) (static inline tuple[])");
        }

        ctx.warn("inline tuple[] used heuristic body decode for tail after offset table mismatch");
        var heuristic = ctx.decodeBody(AbiCodec.slice(tail, 32, remaining));
        return SkeletonShape.inlineTupleArrayElement(elementFieldHints, heuristic,
            count + " elements (heuristic); using element[0] — others may differ");
    }

    private static DecodedArg countedPrimArray(String suffix, String elemType, int count) {
        return new DecodedArg.PrimArray(suffix, List.of(elemType),
            count + ELEMENT_COUNT_NOTE_SUFFIX);
    }

    private static boolean hasAnotherArrayDimension(String elemType) {
        return AbiTypeSyntax.peelOutermostArrayDimension(elemType) != null;
    }

    static boolean looksLikeDynamicArrayCount(byte[] tail) {
        return AbiCodec.looksLikeDynamicArrayCount(tail);
    }

    private static DecodedArg decodeDynamicBytesOrString(String type, byte[] tail) {
        var length = AbiCodec.safeToInt(AbiCodec.uintOf(AbiCodec.slice(tail, 0, 32)),
            BYTES + "/" + STRING + " length");
        return new DecodedArg.Leaf(List.of(type),
            length + " bytes payload (type fixed by skeleton)");
    }

    /**
     * Decodes {@code tuple[]} when the skeleton only names {@code tuple} (no inline field types).
     */
    static DecodedArg decodeOpaqueTupleArray(DecodeContext ctx, byte[] tail, int count) {
        if (count == 0) {
            return new DecodedArg.Tuple("", opaqueTupleElementShape(ctx, tail),
                "empty opaque tuple[]");
        }

        var remaining = tail.length - 32;

        var elemOffsets = OffsetTable.parseMonotonic(tail, count, remaining);
        if (elemOffsets.length == count) {
            DecodedArg shape = null;
            for (var i = 0; i < count; i++) {
                var from = 32 + elemOffsets[i];
                var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : tail.length;
                var elem = GreedyBodyDecoder.decodeTupleElementBody(ctx,
                    AbiCodec.slice(tail, from, to - from));
                if (shape == null) {
                    shape = elem;
                }
            }
            return new DecodedArg.Tuple("",
                shape != null ? tupleFields(shape) : List.of(),
                count + " elements; using element[0] (opaque tuple[])");
        }

        if (remaining > 0 && remaining % count == 0) {
            var elemBytes = remaining / count;
            if (elemBytes >= 32 && elemBytes % 32 == 0) {
                var slots = elemBytes / 32;
                var first = AbiCodec.slice(tail, 32, elemBytes);
                var inner = GreedyBodyDecoder.decodeTupleElementBody(ctx, first);
                return new DecodedArg.Tuple("", tupleFields(inner),
                    count + " elements × " + slots + " slot(s) (static opaque tuple[])");
            }
        }

        if (remaining > 0) {
            var concatenated = GreedyBodyDecoder.tryDecodeConcatenatedArray(ctx, tail, count, remaining);
            if (concatenated instanceof DecodedArg.Tuple t && !t.fields().isEmpty()) {
                return attachOpaqueTupleArrayShape(t, count);
            }
        }

        ctx.warn("opaque tuple[] fell back to offset-table tuple[] decode");
        return decodeDynamicTupleArray(ctx, tail);
    }

    private static List<DecodedArg> tupleFields(DecodedArg decoded) {
        if (decoded instanceof DecodedArg.Tuple t) {
            return t.fields();
        }
        return List.of(decoded);
    }

    /**
     * When count is zero, infer element field shapes from any decodable tail bytes or a single
     * heuristic element decode (structure comparison needs tuple children, not an empty tuple).
     */
    private static List<DecodedArg> opaqueTupleElementShape(DecodeContext ctx, byte[] tail) {
        if (tail.length > 32) {
            var probe = GreedyBodyDecoder.decodeTupleElementBody(ctx,
                AbiCodec.slice(tail, 32, tail.length - 32));
            if (probe instanceof DecodedArg.Tuple t && !t.fields().isEmpty()) {
                return t.fields();
            }
        }
        var heuristic = GreedyBodyDecoder.decodeTupleElementBody(ctx,
            AbiCodec.slice(tail, 0, Math.min(tail.length, 96)));
        return tupleFields(heuristic);
    }

    private static DecodedArg attachOpaqueTupleArrayShape(DecodedArg.Tuple elementShape, int count) {
        return new DecodedArg.Tuple("", elementShape.fields(),
            count + " elements; using element[0] (opaque tuple[])");
    }

    private static DecodedArg decodeDynamicTupleArray(DecodeContext ctx, byte[] tail) {
        if (!looksLikeDynamicArrayCount(tail)) {
            return new DecodedArg.Tuple("",
                ctx.decodeBody(AbiCodec.slice(tail, 32, tail.length - 32)),
                "tuple[] (heuristic; bad count word)");
        }
        var count = AbiCodec.safeToInt(AbiCodec.uintOf(AbiCodec.slice(tail, 0, 32)), "tuple[] count");
        if (count == 0) {
            return new DecodedArg.Tuple("", opaqueTupleElementShape(ctx, tail), "empty tuple[]");
        }

        var remaining = tail.length - 32;
        var elemOffsets = OffsetTable.parseMonotonic(tail, count, remaining);
        if (elemOffsets.length == count) {
            List<DecodedArg> shape = null;
            for (var i = 0; i < count; i++) {
                var from = 32 + elemOffsets[i];
                var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : tail.length;
                var fields = tupleFields(GreedyBodyDecoder.decodeTupleElementBody(ctx,
                    AbiCodec.slice(tail, from, to - from)));
                if (shape == null) {
                    shape = fields;
                }
            }
            return new DecodedArg.Tuple("", shape != null ? shape : List.of(),
                count + " elements; using element[0] (opaque tuple[])");
        }

        var offsets = readTupleArrayOffsets(tail, count);
        var perElement = decodeTupleArrayElements(ctx, tail, count, offsets);
        var shape = perElement.get(0);
        return new DecodedArg.Tuple("", shape,
            count + " elements; using element[0] structure — other elements may differ");
    }

    private static int[] readTupleArrayOffsets(byte[] tail, int count) {
        var offsets = new int[count];
        for (var i = 0; i < count; i++) {
            offsets[i] = AbiCodec.safeToInt(AbiCodec.uintOf(AbiCodec.slice(tail, 32 + i * 32, 32)),
                "tuple[] element offset [" + i + "]");
        }
        return offsets;
    }

    private static List<List<DecodedArg>> decodeTupleArrayElements(DecodeContext ctx, byte[] tail, int count,
        int[] offsets) {
        var perElement = new ArrayList<List<DecodedArg>>(count);
        for (var i = 0; i < count; i++) {
            var from = 32 + offsets[i];
            var to = (i + 1 < count) ? 32 + offsets[i + 1] : tail.length;
            perElement.add(tupleFields(GreedyBodyDecoder.decodeTupleElementBody(ctx,
                AbiCodec.slice(tail, from, to - from))));
        }
        return perElement;
    }
}
