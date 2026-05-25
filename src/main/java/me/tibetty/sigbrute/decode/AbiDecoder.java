package me.tibetty.sigbrute.decode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.expander.TypeExpander;

/**
 * Heuristic ABI calldata decoder. Produces a {@link DecodedArg} tree from a calldata body.
 * Optionally accepts a top-level type skeleton (the list of argument types Etherscan shows, e.g.
 * {@code [uint256, tuple, tuple[]]}) to remove ambiguity at the top level.
 *
 * <p>
 * Below the top level the decoder works from raw bytes alone — head/tail offsets, length-prefix
 * sanity checks, and value-shape inference. Tuples and arrays inside a {@code tuple}/{@code
 * tuple[]} are guessed; the user is expected to refine the emitted YAML.
 */
public final class AbiDecoder {

    private static final String TUPLE = "tuple";
    private static final String BYTES = "bytes";
    private static final String STRING = "string";
    private static final String UINT_WILDCARD = "uint*";

    private AbiDecoder() {
    }

    public static List<DecodedArg> decodeArgs(byte[] body, List<String> topLevelHint) {
        if ((body.length & 31) != 0) {
            throw new IllegalArgumentException("body length not a multiple of 32: " + body.length);
        }

        if (topLevelHint != null && !topLevelHint.isEmpty()) {
            return decodeWithSkeleton(body, topLevelHint);
        }

        return decodeTupleBody(body);
    }

    // ------------------------------------------------------------------
    // Skeleton-guided top-level decode
    // ------------------------------------------------------------------

    private record HeadSection(int headSize, int totalSlots) {
        static HeadSection of(byte[] body) {
            var headSize = scanHeadSize(body);
            if (headSize < 0) {
                headSize = body.length;
            }
            return new HeadSection(headSize, headSize / 32);
        }
    }

    private record SlotDemand(int[] minSlots, int demandSum, int unknownTupleCount) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof SlotDemand other && demandSum == other.demandSum
                && unknownTupleCount == other.unknownTupleCount
                && Arrays.equals(minSlots, other.minSlots);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(minSlots), demandSum, unknownTupleCount);
        }

        @Override
        public String toString() {
            return "SlotDemand[minSlots=" + Arrays.toString(minSlots) + ", demandSum=" + demandSum
                + ", unknownTupleCount=" + unknownTupleCount + ']';
        }
    }

    private record SkeletonLayout(int[] argHeadSlot, List<int[]> dynOffsets) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof SkeletonLayout other
                && Arrays.equals(argHeadSlot, other.argHeadSlot)
                && offsetPairsEqual(dynOffsets, other.dynOffsets);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(argHeadSlot), offsetPairsHash(dynOffsets));
        }

        @Override
        public String toString() {
            return "SkeletonLayout[argHeadSlot=" + Arrays.toString(argHeadSlot) + ", dynOffsets="
                + dynOffsets + ']';
        }

        private static boolean offsetPairsEqual(List<int[]> left, List<int[]> right) {
            if (left.size() != right.size()) {
                return false;
            }

            for (var i = 0; i < left.size(); i++) {
                if (!Arrays.equals(left.get(i), right.get(i))) {
                    return false;
                }
            }

            return true;
        }

        private static int offsetPairsHash(List<int[]> pairs) {
            var hash = 1;
            for (int[] pair : pairs) {
                hash = 31 * hash + Arrays.hashCode(pair);
            }
            return hash;
        }
    }

    private static List<DecodedArg> decodeWithSkeleton(byte[] body, List<String> hint) {
        var head = HeadSection.of(body);
        var demand = computeSlotDemand(hint);
        validateSlotDemand(hint, demand, head.totalSlots());

        var tupleSlots = resolveTupleSlots(body, hint, demand, head);
        absorbRemainingSlack(body, hint, demand.minSlots(), tupleSlots, head);
        var layout = buildSkeletonLayout(body, hint, demand.minSlots(), tupleSlots, head);
        return decodeSkeletonArgs(body, hint, demand.minSlots(), tupleSlots, layout, head);
    }

    private static SlotDemand computeSlotDemand(List<String> hint) {
        var minSlots = new int[hint.size()];
        var demandSum = 0;
        var unknownTupleCount = 0;

        for (var i = 0; i < hint.size(); i++) {
            var t = hint.get(i);
            var s = staticSlotCount(t);
            if (t.equals(TUPLE)) {
                minSlots[i] = 1;
                demandSum += 1;
                unknownTupleCount++;
            } else if (s > 0) {
                minSlots[i] = s;
                demandSum += s;
            } else {
                minSlots[i] = 1;
                demandSum += 1;
            }
        }

        return new SlotDemand(minSlots, demandSum, unknownTupleCount);
    }

    private static void validateSlotDemand(List<String> hint, SlotDemand demand, int totalSlots) {
        var slack = totalSlots - demand.demandSum();
        if (slack < 0 && demand.unknownTupleCount() == 0) {
            throw new IllegalStateException("Skeleton " + hint + " demands " + demand.demandSum()
                + " head slots but body has only " + totalSlots);
        }
    }

    private record TupleSlotResolution(int slots, int cursorAdvance, int slackConsumed) {
    }

    private static int[] resolveTupleSlots(byte[] body, List<String> hint, SlotDemand demand,
        HeadSection head) {
        var tupleSlots = new int[hint.size()];
        var slack = head.totalSlots() - demand.demandSum();
        var cursor = 0;

        for (var i = 0; i < hint.size(); i++) {
            var t = hint.get(i);
            if (t.equals(TUPLE)) {
                var resolution = resolveOneTupleSlot(body, hint, head, slack, i, cursor);
                tupleSlots[i] = resolution.slots();
                cursor += resolution.cursorAdvance();
                slack -= resolution.slackConsumed();
            } else {
                cursor += demand.minSlots()[i];
            }
        }

        return tupleSlots;
    }

    private static TupleSlotResolution resolveOneTupleSlot(byte[] body, List<String> hint,
        HeadSection head, int slack, int index, int cursor) {
        var slot = slice(body, Math.min(cursor, head.totalSlots() - 1) * 32, 32);
        var dynamic = isPlausibleOffset(uintOf(slot), body.length, head.headSize());
        if (dynamic) {
            return new TupleSlotResolution(1, 1, 0);
        }

        var take = countTuplesAfter(hint, index) == 0 ? slack + 1 : 1;
        return new TupleSlotResolution(take, take, take - 1);
    }

    private static int countTuplesAfter(List<String> hint, int fromIndex) {
        var count = 0;
        for (var j = fromIndex + 1; j < hint.size(); j++) {
            if (hint.get(j).equals(TUPLE)) {
                count++;
            }
        }
        return count;
    }

    private static void absorbRemainingSlack(byte[] body, List<String> hint, int[] minSlots,
        int[] tupleSlots, HeadSection head) {
        var slack = head.totalSlots() - headSlotCount(hint, minSlots, tupleSlots);
        if (slack <= 0) {
            return;
        }

        var target = findStaticTupleSlackTarget(hint, body, minSlots, tupleSlots, head);
        if (target >= 0) {
            tupleSlots[target] += slack;
        }
    }

    private static int findStaticTupleSlackTarget(List<String> hint, byte[] body, int[] minSlots,
        int[] tupleSlots, HeadSection head) {
        for (var i = 0; i < hint.size(); i++) {
            if (hint.get(i).equals(TUPLE) && tupleSlots[i] == 1) {
                var slotIndex = slotCursorBeforeIndex(hint, i, minSlots, tupleSlots);
                if (!looksLikeOffsetAt(body, slotIndex, body.length, head.headSize())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int headSlotCount(List<String> hint, int[] minSlots, int[] tupleSlots) {
        var total = 0;
        for (var i = 0; i < hint.size(); i++) {
            total += hint.get(i).equals(TUPLE) ? tupleSlots[i] : minSlots[i];
        }
        return total;
    }

    private static SkeletonLayout buildSkeletonLayout(byte[] body, List<String> hint,
        int[] minSlots, int[] tupleSlots, HeadSection head) {
        var dynOffsets = new ArrayList<int[]>();
        var argHeadSlot = new int[hint.size()];
        var sc = 0;

        for (var i = 0; i < hint.size(); i++) {
            argHeadSlot[i] = sc;
            var t = hint.get(i);
            var take = slotsForHint(t, minSlots[i], tupleSlots[i]);
            if (registersDynOffset(t, take, body, sc, head)) {
                var off = safeToInt(uintOf(slice(body, sc * 32, 32)), "head offset for arg " + i);
                dynOffsets.add(new int[]{i, off});
            }
            sc += take;
        }

        return new SkeletonLayout(argHeadSlot, dynOffsets);
    }

    private static int slotsForHint(String type, int minSlots, int tupleSlots) {
        return type.equals(TUPLE) ? tupleSlots : minSlots;
    }

    private static boolean registersDynOffset(String type, int take, byte[] body, int slotIndex,
        HeadSection head) {
        if (isDynamicHint(type)) {
            return true;
        }
        return type.equals(TUPLE) && take == 1
            && looksLikeOffsetAt(body, slotIndex, body.length, head.headSize());
    }

    private static List<DecodedArg> decodeSkeletonArgs(byte[] body, List<String> hint,
        int[] minSlots, int[] tupleSlots, SkeletonLayout layout, HeadSection head) {
        var out = new ArrayList<DecodedArg>(hint.size());
        for (var i = 0; i < hint.size(); i++) {
            out.add(decodeSkeletonArg(body, hint, i, minSlots, tupleSlots, layout, head));
        }
        return out;
    }

    private static DecodedArg decodeSkeletonArg(byte[] body, List<String> hint, int argIndex,
        int[] minSlots, int[] tupleSlots, SkeletonLayout layout, HeadSection head) {
        var type = hint.get(argIndex);
        var take = slotsForHint(type, minSlots[argIndex], tupleSlots[argIndex]);
        var slotStart = layout.argHeadSlot()[argIndex];

        if (isDynamicHint(type)) {
            return decodeSkeletonDynamicArg(body, type, slotStart, argIndex, layout.dynOffsets());
        }
        if (type.equals(TUPLE)) {
            return decodeSkeletonTupleArg(body, take, slotStart, argIndex, layout.dynOffsets(),
                head);
        }
        return decodeStaticTypedSlots(type, body, slotStart);
    }

    private static DecodedArg decodeSkeletonDynamicArg(byte[] body, String type, int slotStart,
        int argIndex, List<int[]> dynOffsets) {
        var off = safeToInt(uintOf(slice(body, slotStart * 32, 32)),
            "head offset for arg " + argIndex);
        var next = nextDynOffsetAfter(dynOffsets, off, body.length);
        return decodeDynamicTopLevel(type, slice(body, off, next - off));
    }

    private static DecodedArg decodeSkeletonTupleArg(byte[] body, int take, int slotStart,
        int argIndex, List<int[]> dynOffsets, HeadSection head) {
        if (take == 1 && looksLikeOffsetAt(body, slotStart, body.length, head.headSize())) {
            var off = safeToInt(uintOf(slice(body, slotStart * 32, 32)),
                "head offset for tuple arg " + argIndex);
            var next = nextDynOffsetAfter(dynOffsets, off, body.length);
            var tail = slice(body, off, next - off);
            return new DecodedArg.Tuple("", decodeTupleBody(tail),
                "dynamic tuple at offset " + off);
        }

        var fields = new ArrayList<DecodedArg>(take);
        for (var j = 0; j < take; j++) {
            var w = slice(body, (slotStart + j) * 32, 32);
            fields.add(new DecodedArg.Leaf(TypeInferrer.inferStatic(w),
                "field " + j + " — " + TypeInferrer.shapeLabel(w) + " " + TypeInferrer.hexOf(w)));
        }
        return new DecodedArg.Tuple("", fields,
            "static tuple, " + take + " field(s) (inferred from head occupancy)");
    }

    private static int slotCursorBeforeIndex(List<String> hint, int idx, int[] minSlots,
        int[] tupleSlots) {
        var sc = 0;
        for (var j = 0; j < idx; j++) {
            sc += hint.get(j).equals(TUPLE) ? tupleSlots[j] : minSlots[j];
        }
        return sc;
    }

    private static boolean looksLikeOffsetAt(byte[] body, int slotIndex, int bodyLen,
        int headSize) {
        if (slotIndex < 0 || slotIndex * 32 >= bodyLen) {
            return false;
        }

        return isPlausibleOffset(uintOf(slice(body, slotIndex * 32, 32)), bodyLen, headSize);
    }

    private static int nextDynOffsetAfter(List<int[]> dynOffsets, int currentOffset, int bodyLen) {
        var next = bodyLen;
        for (int[] d : dynOffsets) {
            if (d[1] > currentOffset && d[1] < next) {
                next = d[1];
            }
        }
        return next;
    }

    private static boolean isDynamicBytesOrString(String type) {
        return type.equals(BYTES) || type.equals(STRING);
    }

    private static boolean isDynamicHint(String type) {
        if (type.equals(TUPLE)) {
            return false; // resolved separately
        }

        if (isDynamicBytesOrString(type)) {
            return true;
        }

        if (type.endsWith("[]")) {
            return true;
        }

        var parts = splitArraySuffix(type);
        if (parts != null) {
            if (parts.suffix().contains("[]")) {
                return true;
            }

            return isDynamicHint(parts.base());
        }

        return false;
    }

    /** How many head slots a static type occupies. Returns 0 for dynamic types or "tuple". */
    private static int staticSlotCount(String type) {
        if (type.equals(TUPLE)) {
            return 0;
        }

        if (isDynamicBytesOrString(type)) {
            return 0;
        }

        if (isDynamicHint(type)) {
            return 0;
        }

        var parts = splitArraySuffix(type);
        if (parts != null) {
            var total = countFixedArrayElements(parts.suffix());
            if (total == 0) {
                return 0;
            }

            var baseSlots = staticSlotCount(parts.base());
            return baseSlots == 0 ? 0 : baseSlots * total;
        }

        if (isStaticPrimitive(type)) {
            return 1;
        }

        return 0;
    }

    private static DecodedArg decodeDynamicTopLevel(String type, byte[] tail) {
        if (isDynamicBytesOrString(type)) {
            return decodeDynamicBytesOrStringTopLevel(type, tail);
        }

        if (type.endsWith("[]")) {
            var elemType = arrayElementType(type);
            if (elemType.equals(TUPLE)) {
                return decodeDynamicTupleArrayTopLevel(tail);
            }
            // Skeleton declares a concrete primitive-array type (e.g. address[], uint256[]).
            // Do not fall through to heuristic bytes detection — use the declared element type.
            if (isStaticPrimitive(elemType)) {
                return decodeKnownPrimitiveArrayTopLevel(elemType, tail);
            }
        }

        return decodeDynamicField(tail);
    }

    /**
     * Decodes a top-level dynamic primitive array whose element type is known from the skeleton
     * (e.g. {@code address[]}, {@code uint256[]}). Reads the ABI-encoded count word and emits a
     * {@link DecodedArg.PrimArray} with the declared type fixed — no heuristic inference needed,
     * and no confusion with a length-prefixed {@code bytes} value.
     */
    private static DecodedArg decodeKnownPrimitiveArrayTopLevel(String elemType, byte[] tail) {
        var count = safeToInt(uintOf(slice(tail, 0, 32)), "array count");
        return new DecodedArg.PrimArray("[]", List.of(elemType),
            count + " element(s), type fixed by skeleton");
    }

    private static DecodedArg decodeDynamicBytesOrStringTopLevel(String type, byte[] tail) {
        var length = safeToInt(uintOf(slice(tail, 0, 32)), BYTES + "/" + STRING + " length");
        return new DecodedArg.Leaf(List.of(type),
            length + " bytes payload (type fixed by skeleton)");
    }

    private static String arrayElementType(String arrayType) {
        return arrayType.substring(0, arrayType.length() - 2);
    }

    private static DecodedArg decodeDynamicTupleArrayTopLevel(byte[] tail) {
        var count = safeToInt(uintOf(slice(tail, 0, 32)), "tuple[] count");
        if (count == 0) {
            return new DecodedArg.Tuple("[]", List.of(), "empty tuple[]");
        }

        var offsets = readTupleArrayOffsets(tail, count);
        var perElement = decodeTupleArrayElements(tail, count, offsets);
        var shape = perElement.get(0);
        return new DecodedArg.Tuple("[]", shape,
            count + " elements; using element[0] structure — other elements may differ");
    }

    private static int[] readTupleArrayOffsets(byte[] tail, int count) {
        var offsets = new int[count];
        for (var i = 0; i < count; i++) {
            offsets[i] = safeToInt(uintOf(slice(tail, 32 + i * 32, 32)),
                "tuple[] element offset [" + i + "]");
        }
        return offsets;
    }

    private static List<List<DecodedArg>> decodeTupleArrayElements(byte[] tail, int count,
        int[] offsets) {
        var perElement = new ArrayList<List<DecodedArg>>(count);
        for (var i = 0; i < count; i++) {
            var from = 32 + offsets[i];
            var to = (i + 1 < count) ? 32 + offsets[i + 1] : tail.length;
            perElement.add(decodeTupleBody(slice(tail, from, to - from)));
        }
        return perElement;
    }

    private static DecodedArg decodeStaticTypedSlots(String type, byte[] body, int slotStart) {
        // Single-slot primitive — narrow candidates to the declared type (shrinks search space).
        if (isStaticPrimitive(type)) {
            var w = slice(body, slotStart * 32, 32);
            return new DecodedArg.Leaf(List.of(type),
                TypeInferrer.shapeLabel(w) + " " + TypeInferrer.hexOf(w));
        }

        var parts = splitArraySuffix(type);
        if (parts != null && !parts.suffix().contains("[]")) {
            // Already known type; preserve as-is via a Leaf with the full type string.
            // The user can refine if needed.
            var n = staticSlotCount(type);
            var values = new StringBuilder();
            for (var i = 0; i < Math.min(n, 4); i++) {
                if (i > 0) {
                    values.append(", ");
                }
                values.append(TypeInferrer.hexOf(slice(body, (slotStart + i) * 32, 32)));
            }
            if (n > 4) {
                values.append(", …");
            }
            return new DecodedArg.Leaf(List.of(type), n + "-slot fixed array (" + values + ")");
        }
        // Fallback — shouldn't reach here for valid static types.
        var w = slice(body, slotStart * 32, 32);
        return new DecodedArg.Leaf(List.of(type), TypeInferrer.hexOf(w));
    }

    // ------------------------------------------------------------------
    // Free-form tuple body decode
    // ------------------------------------------------------------------

    private static List<DecodedArg> decodeTupleBody(byte[] body) {
        var numWords = body.length / 32;
        if (numWords == 0) {
            return List.of();
        }

        var headSize = scanHeadSize(body);
        if (headSize < 0) {
            return decodeFlatStaticWords(body, numWords);
        }

        return decodeHeadTailTuple(body, headSize);
    }

    private static List<DecodedArg> decodeFlatStaticWords(byte[] body, int numWords) {
        var out = new ArrayList<DecodedArg>(numWords);
        for (var i = 0; i < numWords; i++) {
            var word = slice(body, i * 32, 32);
            out.add(staticWordLeaf(word));
        }
        return out;
    }

    private static List<DecodedArg> decodeHeadTailTuple(byte[] body, int headSize) {
        var numFields = headSize / 32;
        var dynSlots = collectDynamicHeadSlots(body, numFields, headSize);
        var fields = new ArrayList<DecodedArg>(numFields);
        for (var i = 0; i < numFields; i++) {
            var word = slice(body, i * 32, 32);
            fields.add(decodeOneHeadField(body, i, word, headSize, dynSlots));
        }
        return fields;
    }

    private static List<int[]> collectDynamicHeadSlots(byte[] body, int numFields, int headSize) {
        var dynSlots = new ArrayList<int[]>();
        for (var i = 0; i < numFields; i++) {
            var value = uintOf(slice(body, i * 32, 32));
            if (isPlausibleOffset(value, body.length, headSize)) {
                dynSlots.add(new int[]{i, safeToInt(value, "dynSlots offset [" + i + "]")});
            }
        }
        return dynSlots;
    }

    private static DecodedArg decodeOneHeadField(byte[] body, int fieldIndex, byte[] word,
        int headSize, List<int[]> dynSlots) {
        var value = uintOf(word);
        if (!isPlausibleOffset(value, body.length, headSize)) {
            return staticWordLeaf(word);
        }

        var offset = safeToInt(value, "field offset [" + fieldIndex + "]");
        var next = nextDynOffsetAfter(dynSlots, offset, body.length);
        return decodeDynamicField(slice(body, offset, next - offset));
    }

    private static DecodedArg.Leaf staticWordLeaf(byte[] word) {
        return new DecodedArg.Leaf(TypeInferrer.inferStatic(word),
            TypeInferrer.shapeLabel(word) + " " + TypeInferrer.hexOf(word));
    }

    /** Scans body for the smallest plausible head-section size. -1 if no offsets found. */
    private static int scanHeadSize(byte[] body) {
        var numWords = body.length / 32;
        var best = -1;
        for (var i = 0; i < numWords; i++) {
            var offset = candidateHeadOffset(uintOf(slice(body, i * 32, 32)), i, body.length);
            if (offset >= 0 && (best < 0 || offset < best)) {
                best = offset;
            }
        }
        return advanceHeadSizeIfTailLooksLikeOffset(body, best);
    }

    /**
     * Advances {@code headSize} when the first word of the tail is itself a larger plausible
     * head boundary, and the slots between the current and new head boundary confirm it by
     * containing at least one valid ABI offset relative to the new size.
     *
     * <p>This fixes the case where a static tuple field's {@code uint256} value coincidentally
     * satisfies all ABI-offset plausibility checks (aligned multiple of 32, within body length,
     * beyond slot position) and causes {@link #scanHeadSize} to return a head boundary that is
     * too small. In the correct interpretation those slots are static data and the actual dynamic
     * offsets form a cluster that begins further into the head.
     *
     * <p>Example: a 10-field tuple where fields 0–5 are static and fields 6–9 carry ABI offsets.
     * Field 5 has value {@code 0xC0 = 192 = 6×32} which looks like an offset, so
     * {@code scanHeadSize} returns 192. The first "tail" word is then 320 (the real first offset
     * at slot 6). Since 320 is also a plausible head size AND slot 7 (value 416) is a valid ABI
     * offset relative to headSize 320, we advance to 320 — the correct boundary.
     *
     * <p>Guard: the candidate {@code next} must be strictly less than {@code body.length} so that
     * at least one tail word exists after the extended head. The validation step (checking slots
     * strictly after {@code headSize / 32}) prevents false chaining when a bytes/string length
     * word happens to equal a larger multiple of 32 but the surrounding payload contains no
     * plausible offsets.
     */
    private static int advanceHeadSizeIfTailLooksLikeOffset(byte[] body, int headSize) {
        if (headSize < 0) {
            return headSize;
        }
        while (headSize + 32 <= body.length) {
            var v = uintOf(slice(body, headSize, 32));
            if (v.signum() <= 0 || v.bitLength() > 31) {
                break;
            }
            var next = v.intValueExact();
            if (next % 32 != 0 || next <= headSize || next >= body.length) {
                break;
            }
            // Validate: at least one slot in (headSize/32, next/32) must carry a plausible ABI
            // offset relative to 'next'. The slot at headSize/32 (the word that triggered the
            // chain) is excluded to prevent bytes/string length words from self-validating.
            if (!hasPlausibleOffsetInRange(body, headSize / 32 + 1, next / 32, next)) {
                break;
            }
            headSize = next;
        }
        return headSize;
    }

    /**
     * Returns {@code true} when any slot in {@code [fromSlot, toSlot)} (exclusive end) contains
     * a value that {@link #isPlausibleOffset} accepts for the given {@code headSize}.
     */
    private static boolean hasPlausibleOffsetInRange(byte[] body, int fromSlot, int toSlot,
        int headSize) {
        var numWords = body.length / 32;
        for (var i = fromSlot; i < toSlot && i < numWords; i++) {
            if (isPlausibleOffset(uintOf(slice(body, i * 32, 32)), body.length, headSize)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Offset in bytes from start of body, or -1 if the head word is not a plausible tail pointer.
     */
    private static int candidateHeadOffset(BigInteger value, int slotIndex, int bodyLen) {
        if (value.signum() <= 0 || value.bitLength() > 31) {
            return -1;
        }

        var offset = safeToInt(value, "scanHeadSize offset");
        if (offset % 32 != 0 || offset > bodyLen || offset <= slotIndex * 32) {
            return -1;
        }

        return offset;
    }

    private static boolean isPlausibleOffset(BigInteger v, int bodyLen, int headSize) {
        if (v.signum() < 0 || v.bitLength() > 31) {
            return false;
        }

        var off = safeToInt(v, "isPlausibleOffset");
        return off % 32 == 0 && off >= headSize && off + 32 <= bodyLen;
    }

    // ------------------------------------------------------------------
    // Decode a dynamic field body (no type hint)
    // ------------------------------------------------------------------

    private static DecodedArg decodeDynamicField(byte[] body) {
        if (body.length < 32) {
            return new DecodedArg.Leaf(List.of(BYTES), "0 bytes payload");
        }

        var lengthWord = uintOf(slice(body, 0, 32));
        var decoded = tryDecodeLengthPrefixedBytes(body, lengthWord);
        if (decoded != null) {
            return decoded;
        }

        decoded = tryDecodeArrayFromLengthPrefix(body, lengthWord);
        if (decoded != null) {
            return decoded;
        }

        return new DecodedArg.Tuple("", decodeTupleBody(body),
            "fallback dynamic tuple (could also be bytes/string or T[])");
    }

    private static DecodedArg tryDecodeLengthPrefixedBytes(byte[] body, BigInteger lengthWord) {
        if (lengthWord.bitLength() > 31 || 32 + paddedLength(lengthWord) != body.length) {
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

    private static int paddedLength(BigInteger lengthWord) {
        var length = lengthWord.intValueExact();
        return ((length + 31) / 32) * 32;
    }

    private static DecodedArg tryDecodeArrayFromLengthPrefix(byte[] body, BigInteger lengthWord) {
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

        var dynamic = tryDecodeDynamicOffsetArray(body, count, remaining);
        return dynamic != null ? dynamic : tryDecodeConcatenatedArray(body, count, remaining);
    }

    private static DecodedArg tryDecodeDynamicOffsetArray(byte[] body, int count, int remaining) {
        if (remaining < count * 32) {
            return null;
        }

        var elemOffsets = parseMonotonicOffsetTable(body, count, remaining);
        if (elemOffsets.length != count) {
            return null;
        }

        // Disambiguate bytes[] from ()[] before committing to tuple structure.
        // A bytes[] element starts with a length word L where 32 + ceil32(L) == elementSize.
        if (allElementsLookLikeBytes(body, count, elemOffsets)) {
            return new DecodedArg.PrimArray("[]", List.of(BYTES, STRING),
                count + " element(s), bytes[] (each prefixed by length)");
        }

        var perElement = new ArrayList<List<DecodedArg>>(count);
        for (var i = 0; i < count; i++) {
            var from = 32 + elemOffsets[i];
            var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : body.length;
            perElement.add(decodeTupleBody(slice(body, from, to - from)));
        }
        return new DecodedArg.Tuple("[]", perElement.get(0),
            count + " elements (dynamic); using element[0] — others may differ");
    }

    /**
     * Returns {@code true} when every element in the dynamic offset table looks like an ABI-encoded
     * {@code bytes} value: its first word is a length {@code L} and the element size equals {@code
     * 32 + ceil32(L)}.
     *
     * <p>
     * This disambiguates {@code bytes[]} from {@code T[]} where every element is a dynamic tuple
     * — both use a dynamic offset table, but the layout inside each element is structurally
     * different.
     */
    private static boolean allElementsLookLikeBytes(byte[] body, int count, int[] elemOffsets) {
        for (var i = 0; i < count; i++) {
            var from = 32 + elemOffsets[i];
            var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : body.length;
            var elemLen = to - from;
            if (elemLen < 32) {
                return false;
            }

            var lengthWord = uintOf(slice(body, from, 32));
            if (lengthWord.bitLength() > 31) {
                return false;
            }

            var expectedSize = 32 + paddedLength(lengthWord);
            if (expectedSize != elemLen) {
                return false;
            }
        }

        return true;
    }

    private static int[] parseMonotonicOffsetTable(byte[] body, int count, int remaining) {
        var elemOffsets = new int[count];
        var prev = BigInteger.valueOf(count * 32L);
        for (var i = 0; i < count; i++) {
            var offsetWord = uintOf(slice(body, 32 + i * 32, 32));
            if (!isValidDynamicArrayOffset(offsetWord, prev, count, remaining)) {
                return new int[0];
            }

            var offset = offsetWord.intValueExact();
            elemOffsets[i] = offset;
            prev = BigInteger.valueOf(offset);
        }

        return elemOffsets;
    }

    private static boolean isValidDynamicArrayOffset(BigInteger offsetWord, BigInteger prev,
        int count, int remaining) {
        if (offsetWord.bitLength() > 31) {
            return false;
        }

        var offset = offsetWord.intValueExact();
        if (offset % 32 != 0 || offset < count * 32 || offset + 32 > remaining) {
            return false;
        }

        return BigInteger.valueOf(offset).compareTo(prev) >= 0;
    }

    private static DecodedArg tryDecodeConcatenatedArray(byte[] body, int count, int remaining) {
        if (remaining % count != 0) {
            return null;
        }

        var width = remaining / count;
        if (width == 32) {
            return decodePrimitiveArray(body, count);
        }

        if (width > 0 && width % 32 == 0) {
            return decodeStaticTupleArray(body, count, width / 32);
        }

        return null;
    }

    private static DecodedArg decodePrimitiveArray(byte[] body, int count) {
        var baseCands = inferPrimitiveArrayBaseTypes(body, count);
        return new DecodedArg.PrimArray("[]", baseCands, count + " element(s), single-word each");
    }

    private static List<String> inferPrimitiveArrayBaseTypes(byte[] body, int count) {
        List<String> baseCands = null;
        for (var i = 0; i < count; i++) {
            var word = slice(body, 32 + i * 32, 32);
            var inferred = TypeInferrer.inferArrayElement(word);
            baseCands = mergeArrayElementCandidates(baseCands, inferred);
        }
        if (baseCands == null || baseCands.isEmpty()) {
            return List.of(UINT_WILDCARD);
        }

        return baseCands;
    }

    private static List<String> mergeArrayElementCandidates(List<String> baseCands,
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

        return compactUintFloor(intersection);
    }

    /** Expands all patterns in {@code patterns} to their concrete ABI type strings. */
    private static Set<String> expandedSet(List<String> patterns) {
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
    private record UintCompactionPlan(Set<Integer> toRemove, List<String> patterns) {
    }

    static List<String> compactUintFloor(List<String> types) {
        var bits = collectConcreteUintBits(types);
        if (bits.isEmpty()) {
            return types;
        }

        var plan = planUintCompaction(maximalConsecutiveUintRuns(bits));
        if (plan.toRemove().isEmpty()) {
            return types;
        }

        return applyUintCompaction(types, plan);
    }

    private static List<Integer> collectConcreteUintBits(List<String> types) {
        var bits = new ArrayList<Integer>();
        for (var t : types) {
            if (isConcreteUint(t)) {
                bits.add(Integer.parseInt(t.substring(4)));
            }
        }
        bits.sort(null);
        return bits;
    }

    /**
     * Returns maximal consecutive uint bit-width runs (step = 8), each as {@code [first, last]}.
     *
     * @param bits sorted list of concrete uint bit-widths (e.g. [8, 16, 32, 256]); must be
     *             non-empty and sorted in ascending order (caller: {@link #collectConcreteUintBits}
     *             guarantees this via {@code bits.sort(null)})
     */
    private static List<int[]> maximalConsecutiveUintRuns(List<Integer> bits) {
        var runs = new ArrayList<int[]>();
        var s = bits.get(0);
        var e = bits.get(0);

        for (var i = 1; i < bits.size(); i++) {
            var b = bits.get(i);
            if (b == e + 8) {
                e = b;
            } else {
                runs.add(new int[]{s, e});
                s = b;
                e = b;
            }
        }

        runs.add(new int[]{s, e});
        return runs;
    }

    private static UintCompactionPlan planUintCompaction(List<int[]> runs) {
        var toRemove = new LinkedHashSet<Integer>();
        var patterns = new ArrayList<String>();
        for (var run : runs) {
            planSingleUintRun(run[0], run[1], toRemove, patterns);
        }
        return new UintCompactionPlan(toRemove, patterns);
    }

    private static void planSingleUintRun(int first, int last, Set<Integer> toRemove,
        List<String> patterns) {
        if (first == last) {
            return;
        }

        var atBottom = (first == 8);
        var atTop = (last == 256);
        if (!atBottom && !atTop) {
            return; // bounded on both sides — no single-token representation
        }

        addUintRange(toRemove, first, last);
        if (atBottom && atTop) {
            patterns.add(UINT_WILDCARD);
        } else if (atTop) {
            patterns.add("uint" + first + "+");
        } else {
            patterns.add("uint" + last + "-");
        }
    }

    private static List<String> applyUintCompaction(List<String> types, UintCompactionPlan plan) {
        var result = new ArrayList<String>();
        for (var t : types) {
            if (isConcreteUint(t) && plan.toRemove().contains(Integer.parseInt(t.substring(4)))) {
                continue;
            }
            result.add(t);
        }
        result.addAll(plan.patterns());
        return result;
    }

    /** Adds every multiple of 8 in [{@code first}, {@code last}] to {@code set}. */
    private static void addUintRange(Set<Integer> set, int first, int last) {
        for (var n = first; n <= last; n += 8) {
            set.add(n);
        }
    }

    /** True for bare concrete uint types like {@code uint8}, {@code uint256} (no +/-/*). */
    private static boolean isConcreteUint(String t) {
        if (!t.startsWith("uint")) {
            return false;
        }

        var rest = t.substring(4);
        return !rest.isEmpty() && isAsciiDigits(rest);
    }

    private static DecodedArg decodeStaticTupleArray(byte[] body, int count, int slotsPerElem) {
        var width = slotsPerElem * 32;
        var firstElem = slice(body, 32, width);
        var fields = new ArrayList<DecodedArg>(slotsPerElem);
        for (var j = 0; j < slotsPerElem; j++) {
            var w = slice(firstElem, j * 32, 32);
            fields.add(new DecodedArg.Leaf(TypeInferrer.inferStatic(w),
                "field " + j + " — " + TypeInferrer.shapeLabel(w)));
        }
        return new DecodedArg.Tuple("[]", fields,
            count + " elements × " + slotsPerElem + " field(s) (static tuple[])");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static byte[] slice(byte[] src, int off, int len) {
        if (off < 0 || len < 0 || off + len > src.length) {
            throw new IllegalArgumentException(
                "slice out of range: off=" + off + " len=" + len + " src.len=" + src.length);
        }
        var out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    static BigInteger uintOf(byte[] word) {
        return new BigInteger(1, word);
    }

    private static int safeToInt(BigInteger v, String context) {
        if (v.bitLength() > 31) {
            throw new IllegalArgumentException(
                context + ": value 0x" + v.toString(16) + " exceeds int range");
        }

        return v.intValueExact();
    }

    /** Base type and trailing {@code [N]} / {@code []} dimensions, e.g. {@code uint256[3][4]}. */
    private record ArraySuffixParts(String base, String suffix) {
    }

    /**
     * Splits a type into base + bracket suffix without regex (avoids nested-quantifier
     * backtracking). Returns null when brackets are absent or malformed.
     */
    private static ArraySuffixParts splitArraySuffix(String type) {
        var bracket = type.indexOf('[');
        if (bracket < 0) {
            return null;
        }

        var base = type.substring(0, bracket);
        var suffix = type.substring(bracket);
        var pos = 0;
        while (pos < suffix.length()) {
            if (suffix.charAt(pos) != '[') {
                return null;
            }

            var end = suffix.indexOf(']', pos);
            if (end < 0) {
                return null;
            }

            var inner = suffix.substring(pos + 1, end);
            if (!inner.isEmpty() && !isAsciiDigits(inner)) {
                return null;
            }

            pos = end + 1;
        }
        return new ArraySuffixParts(base, suffix);
    }

    /** Product of fixed-size dimension lengths; 0 if any segment is {@code []} or invalid. */
    private static int countFixedArrayElements(String suffix) {
        var total = 1;
        var pos = 0;
        while (pos < suffix.length()) {
            if (suffix.charAt(pos) != '[') {
                return 0;
            }

            var end = suffix.indexOf(']', pos);
            if (end < 0) {
                return 0;
            }

            var n = suffix.substring(pos + 1, end);
            if (n.isEmpty()) {
                return 0;
            }

            total *= Integer.parseInt(n);
            pos = end + 1;
        }
        return total;
    }

    private static boolean isAsciiDigits(String s) {
        for (var i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStaticPrimitive(String type) {
        return switch (type) {
            case "address", "bool" -> true;
            default -> isSizedInt(type, "uint") || isSizedInt(type, "int") || isBytesN(type)
                || isFixedMxN(type);
        };
    }

    private static boolean isSizedInt(String type, String prefix) {
        if (!type.startsWith(prefix)) {
            return false;
        }

        var rest = type.substring(prefix.length());
        if (rest.isEmpty()) {
            return true;
        }

        return isAsciiDigits(rest);
    }

    private static boolean isBytesN(String type) {
        if (!type.startsWith(BYTES)) {
            return false;
        }

        var rest = type.substring(BYTES.length());
        if (rest.isEmpty() || !isAsciiDigits(rest)) {
            return false;
        }

        var n = Integer.parseInt(rest);
        return n >= 1 && n <= 32;
    }

    private static boolean isFixedMxN(String type) {
        var start = fixedTypePrefixEnd(type);
        if (start < 0) {
            return false;
        }

        var x = type.indexOf('x', start);
        if (x <= start) {
            return false;
        }

        var mPart = type.substring(start, x);
        var nPart = type.substring(x + 1);
        return !mPart.isEmpty() && isAsciiDigits(mPart) && !nPart.isEmpty() && isAsciiDigits(nPart);
    }

    /** Index after {@code fixed} / {@code ufixed} prefix, or -1 if neither matches. */
    private static int fixedTypePrefixEnd(String type) {
        if (type.startsWith("ufixed")) {
            return 6;
        }

        if (type.startsWith("fixed")) {
            return 5;
        }

        return -1;
    }
}
