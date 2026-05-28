package me.tibetty.sigbrute.decode.strategy.body;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.abi.ArraySuffixComposer;
import me.tibetty.sigbrute.decode.abi.UintPatternCompactor;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.decode.layout.DynamicHeadSlots;
import me.tibetty.sigbrute.decode.layout.OffsetTable;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;
import me.tibetty.sigbrute.expander.TypeExpander;

/** Heuristic tuple-body decoder (no Etherscan type skeleton). */
public final class GreedyBodyDecoder {

    private static final String BYTES = AbiTypeSyntax.BYTES;
    private static final String STRING = AbiTypeSyntax.STRING;
    private static final String UINT_WILDCARD = UintPatternCompactor.UINT_WILDCARD;

    private GreedyBodyDecoder() {
    }

    public static List<DecodedArg> decode(DecodeContext ctx, byte[] body) {
        var numWords = body.length / 32;
        if (numWords == 0) {
            return List.of();
        }

        var headSize = AbiCodec.scanHeadSize(body);
        if (headSize < 0) {
            return decodeFlatStaticWords(ctx, body, numWords);
        }

        return decodeHeadTailTuple(ctx, body, headSize);
    }

    /**
     * Tries each plausible head boundary and keeps the highest-scoring head/tail parse (penalizes
     * bare leaves where a dynamic array or tuple was expected). Used for opaque tuple interiors.
     */
    public static List<DecodedArg> decodeBestHeadTailTuple(DecodeContext ctx, byte[] body) {
        var sizes = headSizeCandidates(body);
        if (sizes.isEmpty()) {
            return decodeFlatStaticWords(ctx, body, body.length / 32);
        }
        List<DecodedArg> bestFields = null;
        var bestScore = Integer.MIN_VALUE;
        for (var headSize : sizes) {
            var numFields = headSize / 32;
            var dynSlots = DynamicHeadSlots.collect(body, numFields, headSize);
            var fields = decodeHeadTailTuple(ctx, body, headSize);
            var score = scoreHeadTailParse(body, headSize, fields, dynSlots);
            if (score > bestScore) {
                bestScore = score;
                bestFields = fields;
            }
        }
        return bestFields != null ? bestFields : List.of();
    }

    static int scoreHeadTailParse(byte[] body, int headSize, List<DecodedArg> fields,
        List<int[]> dynSlots) {
        var score = 500 + dynSlots.size() * 20 - (body.length / 32 - headSize / 32);
        for (var field : fields) {
            if (field instanceof DecodedArg.Leaf) {
                score -= 50;
            }
        }
        return score;
    }

    public static List<DecodedArg> decodeFlatStaticWords(DecodeContext ctx, byte[] body, int numWords) {
        var out = new ArrayList<DecodedArg>(numWords);
        for (var i = 0; i < numWords; i++) {
            var word = AbiCodec.slice(body, i * 32, 32);
            out.add(staticWordLeaf(ctx, word));
        }
        return out;
    }

    public static List<DecodedArg> decodeHeadTailTuple(DecodeContext ctx, byte[] body, int headSize) {
        var numFields = headSize / 32;
        var dynSlots = DynamicHeadSlots.collect(body, numFields, headSize);
        var fields = new ArrayList<DecodedArg>(numFields);
        for (var i = 0; i < numFields; i++) {
            var word = AbiCodec.slice(body, i * 32, 32);
            fields.add(decodeOneHeadField(ctx, body, i, word, headSize, dynSlots));
        }
        return fields;
    }

    static DecodedArg decodeOneHeadField(DecodeContext ctx, byte[] body, int fieldIndex, byte[] word,
        int headSize, List<int[]> dynSlots) {
        var value = AbiCodec.uintOf(word);
        if (!AbiCodec.isPlausibleOffset(value, body.length, headSize)) {
            return staticWordLeaf(ctx, word);
        }

        var offset = AbiCodec.safeToInt(value, "field offset [" + fieldIndex + "]");
        var next = AbiCodec.nextDynOffsetAfter(dynSlots, offset, body.length);
        return ctx.decodeDynamic(AbiCodec.slice(body, offset, next - offset));
    }

    static DecodedArg.Leaf staticWordLeaf(DecodeContext ctx, byte[] word) {
        return new DecodedArg.Leaf(ctx.inferStatic(word),
            TypeInferrer.shapeLabel(word) + " " + TypeInferrer.hexOf(word));
    }

    /** Scans body for the smallest plausible head-section size. -1 if no offsets found. */
    public static DecodedArg decodeDynamicField(DecodeContext ctx, byte[] body) {
        if (body.length < 32) {
            return new DecodedArg.Leaf(List.of(BYTES), "0 bytes payload");
        }

        var lengthWord = AbiCodec.uintOf(AbiCodec.slice(body, 0, 32));
        var decoded = tryDecodeLengthPrefixedBytes(body, lengthWord);
        if (decoded != null) {
            return decoded;
        }

        decoded = tryDecodeArrayFromLengthPrefix(ctx, body, lengthWord);
        if (decoded != null) {
            return decoded;
        }

        ctx.warn("dynamic tail decoded as fallback tuple — may also be bytes, string, or T[]");
        return new DecodedArg.Tuple("", ctx.decodeBody(body),
            "fallback dynamic tuple (could also be bytes/string or T[])");
    }

    public static DecodedArg tryDecodeLengthPrefixedBytes(byte[] body, BigInteger lengthWord) {
        if (lengthWord.bitLength() > 31 || 32 + OffsetTable.paddedLength(lengthWord) != body.length) {
            return null;
        }

        var length = lengthWord.intValueExact();

        // When length == 1 the 64-byte layout is byte-for-byte identical to T[](1).
        // Genuine bytes(1) ABI-encodes its single content byte left-aligned: the first
        // byte of the content word (body[32]) equals the content byte.  If body[32] is
        // zero the content cannot be a non-zero left-aligned bytes(1) value, so the slot
        // is more likely a right-aligned address/uint array element.  Defer to
        // tryDecodeArrayFromLengthPrefix, which will recover the correct T[](1) structure.
        if (length == 1 && body[32] == 0) {
            return null;
        }

        if (length == 0 && body.length == 32) {
            return null;
        }

        if (length > 0) {
            return new DecodedArg.Leaf(List.of(BYTES, STRING),
                length + " bytes — could also be " + BYTES + Math.min(length, 32) + " if static");
        }

        return new DecodedArg.Leaf(List.of(BYTES, STRING), "empty " + BYTES + "/" + STRING);
    }

    public static DecodedArg tryDecodeArrayFromLengthPrefix(DecodeContext ctx, byte[] body,
        BigInteger lengthWord) {
        if (lengthWord.bitLength() > 20) {
            return null;
        }

        var count = lengthWord.intValueExact();
        if (count == 0) {
            if (body.length == 32) {
                return new DecodedArg.Leaf(List.of(BYTES, STRING), "empty " + BYTES + "/" + STRING);
            }
            return new DecodedArg.PrimArray("[]", List.of("address", UINT_WILDCARD), "empty array");
        }

        var remaining = body.length - 32;
        if (remaining <= 0) {
            return null;
        }

        var dynamic = tryDecodeDynamicOffsetArray(ctx, body, count, remaining);
        return dynamic != null ? dynamic : tryDecodeConcatenatedArray(ctx, body, count, remaining);
    }

    static DecodedArg tryDecodeDynamicOffsetArray(DecodeContext ctx, byte[] body, int count, int remaining) {
        if (remaining < count * 32) {
            return null;
        }

        var elemOffsets = OffsetTable.parseMonotonic(body, count, remaining);
        if (elemOffsets.length != count) {
            return null;
        }

        // Disambiguate bytes[] from ()[] before committing to tuple structure.
        // A bytes[] element starts with a length word L where 32 + ceil32(L) == elementSize.
        if (OffsetTable.allElementsLookLikeBytes(body, count, elemOffsets)) {
            return new DecodedArg.PrimArray("[]", List.of(BYTES, STRING),
                count + " element(s), bytes[] (each prefixed by length)");
        }

        DecodedArg shape = null;
        for (var i = 0; i < count; i++) {
            var from = 32 + elemOffsets[i];
            var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : body.length;
            var elem = decodeOffsetIndexedElement(ctx, AbiCodec.slice(body, from, to - from));
            if (shape == null) {
                shape = elem;
            }
        }
        if (shape == null) {
            return null;
        }
        return ArraySuffixComposer.attach(shape, "[]");
    }

    static DecodedArg decodeOffsetIndexedElement(DecodeContext ctx, byte[] elemSlice) {
        var rowArray = tryDecodeOffsetPrefixedRowArray(ctx, elemSlice);
        if (rowArray != null) {
            return rowArray;
        }

        if (elemSlice.length == 32 && AbiCodec.looksLikeDynamicArrayCount(elemSlice)
            && AbiCodec.uintOf(AbiCodec.slice(elemSlice, 0, 32)).signum() == 0) {
            return new DecodedArg.PrimArray("[]", List.of(BYTES, UINT_WILDCARD),
                "empty dynamic array element (nested matrix row)");
        }

        for (var headSize : headSizeCandidates(elemSlice)) {
            var fields = decodeHeadTailTuple(ctx, elemSlice, headSize);
            if (fields.size() > 1) {
                return new DecodedArg.Tuple("", fields,
                    fields.size() + " field(s) (offset-indexed tuple element)");
            }
        }

        var arrayElement = tryDecodeArrayElementSlice(ctx, elemSlice);
        if (arrayElement != null) {
            return arrayElement;
        }

        return decodeTupleElementBody(ctx, elemSlice);
    }

    private static List<Integer> headSizeCandidates(byte[] body) {
        var sizes = new LinkedHashSet<Integer>();
        var headSize = AbiCodec.scanHeadSize(body);
        if (headSize < 0) {
            return List.of();
        }
        while (headSize > 0 && headSize < body.length) {
            sizes.add(headSize);
            var next = AbiCodec.nextHeadSizeFromTailWord(body, headSize);
            if (next < 0 || next == headSize) {
                break;
            }
            headSize = next;
        }
        return new ArrayList<>(sizes);
    }

    static DecodedArg tryDecodeArrayElementSlice(DecodeContext ctx, byte[] elemSlice) {
        if (!AbiCodec.looksLikeDynamicArrayCount(elemSlice)) {
            return null;
        }

        var lengthWord = AbiCodec.uintOf(AbiCodec.slice(elemSlice, 0, 32));
        var count = lengthWord.intValueExact();
        var remaining = elemSlice.length - 32;

        var nested = tryDecodeNestedArraySlice(ctx, elemSlice, lengthWord, count, remaining);
        if (nested != null) {
            return nested;
        }

        return tryDecodeStaticConcatenatedElementSlice(ctx, elemSlice, count, remaining);
    }

    static DecodedArg tryDecodeNestedArraySlice(DecodeContext ctx, byte[] elemSlice,
        BigInteger lengthWord, int count, int remaining) {
        if (count != 0
            && OffsetTable.parseMonotonic(elemSlice, count, remaining).length != count) {
            return null;
        }

        return tryDecodeArrayFromLengthPrefix(ctx, elemSlice, lengthWord);
    }

    static DecodedArg tryDecodeStaticConcatenatedElementSlice(DecodeContext ctx, byte[] elemSlice,
        int count, int remaining) {
        if (count <= 0 || remaining <= 0 || remaining % count != 0) {
            return null;
        }

        var width = remaining / count;
        if (width == 32) {
            return decodePrimitiveArray(ctx, elemSlice, count);
        }
        if (width > 32 && width % 32 == 0) {
            return decodeStaticTupleArray(ctx, elemSlice, count, width / 32);
        }

        return null;
    }

    public static DecodedArg decodeTupleElementBody(DecodeContext ctx, byte[] elemSlice) {
        for (var headSize : headSizeCandidates(elemSlice)) {
            var headTailFields = decodeHeadTailTuple(ctx, elemSlice, headSize);
            if (headTailFields.size() > 1) {
                return new DecodedArg.Tuple("", headTailFields,
                    headTailFields.size() + " field(s) (tuple element, head/tail)");
            }
        }

        var fields = ctx.decodeBody(elemSlice);
        if (fields.isEmpty()) {
            return new DecodedArg.Tuple("", List.of(), "empty tuple element");
        }

        return new DecodedArg.Tuple("", fields,
            fields.size() + " field(s) (dynamic tuple element)");
    }

    static DecodedArg tryDecodeOffsetPrefixedRowArray(DecodeContext ctx, byte[] elemSlice) {
        if (elemSlice.length != 64) {
            return null;
        }

        var header = AbiCodec.uintOf(AbiCodec.slice(elemSlice, 0, 32));
        if (header.intValueExact() != 32) {
            return null;
        }

        var tail = AbiCodec.slice(elemSlice, 32, 32);
        if (!AbiCodec.looksLikeDynamicArrayCount(tail)) {
            return null;
        }

        return tryDecodeArrayFromLengthPrefix(ctx, tail, AbiCodec.uintOf(AbiCodec.slice(tail, 0, 32)));
    }

    public static DecodedArg tryDecodeConcatenatedArray(DecodeContext ctx, byte[] body, int count,
        int remaining) {
        if (remaining % count != 0) {
            return null;
        }

        var width = remaining / count;
        if (width == 32) {
            return decodePrimitiveArray(ctx, body, count);
        }

        if (width > 0 && width % 32 == 0) {
            if (OffsetTable.parseMonotonic(body, count, remaining).length == count) {
                return tryDecodeDynamicOffsetArray(ctx, body, count, remaining);
            }
            return decodeStaticTupleArray(ctx, body, count, width / 32);
        }

        return null;
    }

    static DecodedArg decodePrimitiveArray(DecodeContext ctx, byte[] body, int count) {
        var baseCands = inferPrimitiveArrayBaseTypes(ctx, body, count);
        return new DecodedArg.PrimArray("[]", baseCands, count + " element(s), single-word each");
    }

    static List<String> inferPrimitiveArrayBaseTypes(DecodeContext ctx, byte[] body, int count) {
        List<String> baseCands = null;
        for (var i = 0; i < count; i++) {
            var word = AbiCodec.slice(body, 32 + i * 32, 32);
            var inferred = ctx.inferStatic(word);
            baseCands = mergeArrayElementCandidates(baseCands, inferred);
        }
        if (baseCands == null || baseCands.isEmpty()) {
            return List.of(UINT_WILDCARD);
        }

        return baseCands;
    }

    static List<String> mergeArrayElementCandidates(List<String> baseCands,
        List<String> inferred) {
        if (baseCands == null) {
            return new ArrayList<>(inferred);
        }
        // Expand patterns before intersecting so that overlapping ranges (e.g. "uint184+" and
        // "uint256") are recognised as common concrete types. After intersection, compact any
        // consecutive uint floor run (uintN..uint256) back to a "uintN+" pattern so the YAML
        // stays readable (e.g. "address, uint160+" instead of 14 individual uint lines).
        var leftExpanded = expandedSet(baseCands);
        var rightExpanded = expandedSet(inferred);
        var intersection = new ArrayList<String>();
        for (var t : leftExpanded) {
            if (rightExpanded.contains(t)) {
                intersection.add(t);
            }
        }
        if (intersection.isEmpty()) {
            return baseCands;
        }

        return UintPatternCompactor.compact(intersection);
    }

    /** Expands all patterns in {@code patterns} to their concrete ABI type strings. */
    static Set<String> expandedSet(List<String> patterns) {
        var result = new LinkedHashSet<String>();
        for (var p : patterns) {
            result.addAll(TypeExpander.expand(p));
        }
        return result;
    }

    /**
     * Collapses maximal consecutive {@code uint} runs in {@code types} to compact YAML-readable
     * patterns, preserving all non-uint types in place.
     *
     * <p>
     * Each maximal run is compacted independently:
     *
     * <ul>
     * <li>uint8..uint256 (full range) → {@code uint*}
     * <li>uintN..uint256 (N &gt; 8) → {@code uintN+} (floor: value needs ≥ N bits)
     * <li>uint8..uintN (N &lt; 256) → {@code uintN-} (ceiling: value fits in ≤ N bits)
     * <li>bounded on both sides, or singleton — left as individual type strings
     * </ul>
     *
     * <p>
     * Correctness invariant: only the concrete uints belonging to a compacted run are removed;
     * any uints outside those runs remain in the result unchanged.
     */
    static DecodedArg decodeStaticTupleArray(DecodeContext ctx, byte[] body, int count, int slotsPerElem) {
        var width = slotsPerElem * 32;
        var firstElem = AbiCodec.slice(body, 32, width);
        if (slotsPerElem > 1) {
            var inner = decodeTupleElementBody(ctx, firstElem);
            if (inner instanceof DecodedArg.Tuple t && !t.fields().isEmpty()) {
                return new DecodedArg.Tuple("[]", t.fields(),
                    count + " elements × " + slotsPerElem + " slot(s) (static tuple[])");
            }
        }
        var fields = new ArrayList<DecodedArg>(slotsPerElem);
        for (var j = 0; j < slotsPerElem; j++) {
            var w = AbiCodec.slice(firstElem, j * 32, 32);
            fields.add(new DecodedArg.Leaf(ctx.inferStatic(w),
                "field " + j + " — " + TypeInferrer.shapeLabel(w)));
        }
        return new DecodedArg.Tuple("[]", fields,
            count + " elements × " + slotsPerElem + " field(s) (static tuple[])");
    }

}
