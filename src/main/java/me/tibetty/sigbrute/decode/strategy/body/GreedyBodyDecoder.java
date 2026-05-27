package me.tibetty.sigbrute.decode.strategy.body;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
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

        var perElement = new ArrayList<List<DecodedArg>>(count);
        for (var i = 0; i < count; i++) {
            var from = 32 + elemOffsets[i];
            var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : body.length;
            perElement.add(ctx.decodeBody(AbiCodec.slice(body, from, to - from)));
        }
        return new DecodedArg.Tuple("[]", perElement.get(0),
            count + " elements (dynamic); using element[0] — others may differ");
    }

    static DecodedArg tryDecodeConcatenatedArray(DecodeContext ctx, byte[] body, int count, int remaining) {
        if (remaining % count != 0) {
            return null;
        }

        var width = remaining / count;
        if (width == 32) {
            return decodePrimitiveArray(ctx, body, count);
        }

        if (width > 0 && width % 32 == 0) {
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
