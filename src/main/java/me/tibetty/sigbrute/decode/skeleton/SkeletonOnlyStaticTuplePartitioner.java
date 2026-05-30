package me.tibetty.sigbrute.decode.skeleton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import me.tibetty.sigbrute.decode.strategy.body.GreedyBodyDecoder;

/**
 * Skeleton-only layout: partitions consecutive static opaque {@code tuple} head spans when inline
 * {@code Function:} types are unavailable.
 *
 * <p>Companion paths for other skeleton-only shapes: dynamic {@code tuple[]} via
 * {@link SkeletonArrayDecoder#decodeOpaqueTupleArray} ({@link SkeletonTypeKind#OPAQUE_TUPLE_ARRAY}),
 * and opaque tuple bodies via
 * {@link me.tibetty.sigbrute.decode.strategy.body.GreedyBodyDecoder#decodeBestHeadTailOpaqueTuple}.
 *
 * <p>When several top-level {@code tuple} parameters share one static head region (e.g. a small
 * inner tuple plus a large {@code bytes32[N]} field), default one-slot-per-arg resolution is wrong.
 * This class scores candidate slot splits and writes per-arg widths into {@code tupleSlots} before
 * {@link SkeletonLayout#build}.
 */
final class SkeletonOnlyStaticTuplePartitioner {

    /** Minimum static head span (slots) before attempting partition search. */
    private static final int MIN_PARTITION_REGION_SLOTS = 16;

    private static final int MAX_PARTITION_ENUMERATIONS = 256;

    private static final int SCORE_SMALL_TUPLE_SPAN_MIN = 2;
    private static final int SCORE_SMALL_TUPLE_SPAN_MAX = 6;
    private static final int SCORE_LARGE_STATIC_SPAN_MIN = 16;
    private static final int SCORE_SMALL_LARGE_PAIR_BONUS = 600;

    private SkeletonOnlyStaticTuplePartitioner() {
    }

    static void refine(byte[] body, List<String> layoutHint, int[] minSlots, int[] tupleSlots,
        SkeletonLayout.HeadSection head) {
        if (!usesOnlyOpaqueTuples(layoutHint)) {
            return;
        }

        var i = 0;
        while (i < layoutHint.size()) {
            if (SkeletonTypes.TUPLE.equals(layoutHint.get(i))) {
                i = partitionOpaqueTupleRun(body, layoutHint, minSlots, tupleSlots, head, i);
            } else {
                i++;
            }
        }
    }

    private static int partitionOpaqueTupleRun(byte[] body, List<String> layoutHint, int[] minSlots,
        int[] tupleSlots, SkeletonLayout.HeadSection head, int index) {
        var regionStart = SkeletonLayout.slotCursorBeforeIndex(layoutHint, index, minSlots, tupleSlots);
        if (isDynamicAt(body, regionStart, head)) {
            return index + 1;
        }

        var runStart = index;
        var i = index;
        while (i < layoutHint.size() && SkeletonTypes.TUPLE.equals(layoutHint.get(i))) {
            i++;
        }
        var runLen = i - runStart;
        if (runLen > 1) {
            var regionEnd = staticRegionEnd(body, layoutHint, minSlots, tupleSlots, head, i, regionStart);
            var available = regionEnd - regionStart;
            if (available >= MIN_PARTITION_REGION_SLOTS) {
                assignPartition(body, tupleSlots, runStart, runLen, regionStart, available);
            }
        }
        return i;
    }

    private static boolean usesOnlyOpaqueTuples(List<String> layoutHint) {
        for (var type : layoutHint) {
            if (SkeletonTypes.isInlineTuple(type)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDynamicAt(byte[] body, int slotStart, SkeletonLayout.HeadSection head) {
        var headBound = SkeletonLayout.headBoundForSlot(slotStart, head.headSize());
        return AbiCodec.looksLikeOffsetAt(body, slotStart, body.length, headBound);
    }

    private static void assignPartition(byte[] body, int[] tupleSlots, int runStart, int runLen,
        int regionStart, int available) {
        if (available < runLen) {
            return;
        }

        var ctx = DecodeStrategy.newContext(DecodeStrategy.GREEDY);
        var partition = bestPartition(ctx, body, regionStart, available, runLen);
        if (partition == null) {
            return;
        }

        for (var k = 0; k < runLen; k++) {
            tupleSlots[runStart + k] = partition[k];
        }
    }

    private static int staticRegionEnd(byte[] body, List<String> layoutHint, int[] minSlots,
        int[] tupleSlots, SkeletonLayout.HeadSection head, int runEnd, int regionStart) {
        var maxEnd = head.totalSlots();
        for (var j = runEnd; j < layoutHint.size(); j++) {
            maxEnd -= SkeletonLayout.slotsForHint(layoutHint.get(j), minSlots[j], tupleSlots[j]);
        }
        var end = regionStart + 1;
        while (end < maxEnd) {
            if (AbiCodec.looksLikeOffsetAt(body, end, body.length, head.headSize())) {
                break;
            }
            end++;
        }
        return end;
    }

    private static int[] bestPartition(DecodeContext ctx, byte[] body, int slotStart, int available,
        int tupleCount) {
        if (tupleCount == 1) {
            return new int[]{available};
        }

        int[] best = null;
        var bestScore = Integer.MIN_VALUE;
        for (var partition : enumeratePartitions(available, tupleCount)) {
            var score = scorePartition(ctx, body, slotStart, partition);
            if (score > bestScore) {
                bestScore = score;
                best = partition;
            }
        }
        return best;
    }

    private static boolean scoresSmallLargePair(int a, int b) {
        return (a >= SCORE_SMALL_TUPLE_SPAN_MIN && a <= SCORE_SMALL_TUPLE_SPAN_MAX
                && b >= SCORE_LARGE_STATIC_SPAN_MIN)
            || (b >= SCORE_SMALL_TUPLE_SPAN_MIN && b <= SCORE_SMALL_TUPLE_SPAN_MAX
                && a >= SCORE_LARGE_STATIC_SPAN_MIN);
    }

    private static int scorePartition(DecodeContext ctx, byte[] body, int slotStart, int[] partition) {
        var score = 0;
        var cursor = slotStart;
        for (var slots : partition) {
            score += scoreStaticSpan(ctx, body, cursor, slots);
            cursor += slots;
        }
        if (partition.length == 2) {
            var a = partition[0];
            var b = partition[1];
            if (scoresSmallLargePair(a, b)) {
                score += SCORE_SMALL_LARGE_PAIR_BONUS;
            }
        }
        return score;
    }

    private static int scoreStaticSpan(DecodeContext ctx, byte[] body, int slotStart, int slots) {
        if (slots <= 0) {
            return -10_000;
        }
        if (slots == 1) {
            return 40;
        }

        var span = AbiCodec.slice(body, slotStart * 32, slots * 32);
        if (hasInteriorOffset(span, slots)) {
            var fields = GreedyBodyDecoder.decodeBestHeadTailTuple(ctx, span);
            return scoreDecodedFields(fields, slots);
        }

        if (slots >= 3 && tryFixedArrayShape(slots) != null) {
            return 350 + slots;
        }

        if (slots == 2) {
            var tuple = GreedyBodyDecoder.decodeTupleElementBody(ctx, span);
            var fields = tupleFields(tuple);
            if (fields.size() == 2) {
                return 420;
            }
        }

        var tuple = GreedyBodyDecoder.decodeTupleElementBody(ctx, span);
        return scoreDecodedFields(tupleFields(tuple), slots);
    }

    private static boolean hasInteriorOffset(byte[] span, int slots) {
        for (var slot = 1; slot < slots; slot++) {
            if (AbiCodec.looksLikeOffsetAt(span, slot, span.length, span.length)) {
                return true;
            }
        }
        return false;
    }

    private static List<DecodedArg> tupleFields(DecodedArg decoded) {
        if (decoded instanceof DecodedArg.Tuple t) {
            return t.fields();
        }
        return List.of(decoded);
    }

    private static int scoreDecodedFields(List<DecodedArg> fields, int slots) {
        if (fields.isEmpty()) {
            return -100;
        }
        var score = 200;
        if (fields.size() == slots) {
            score -= 30 * slots;
        }
        if (fields.size() == 1 && fields.get(0) instanceof DecodedArg.PrimArray pa
            && !pa.arraySuffix().isEmpty() && !pa.arraySuffix().contains("[]")) {
            var type = pa.baseCandidates().get(0) + pa.arraySuffix();
            if (AbiTypeSyntax.staticSlotCount(type) == slots) {
                score += 400;
            }
        }
        score -= fields.size() * 5;
        for (var field : fields) {
            if (field instanceof DecodedArg.Tuple || field instanceof DecodedArg.PrimArray) {
                score += 25;
            }
        }
        if (fields.size() >= 2 && fields.size() <= slots) {
            score += 80;
        }
        return score;
    }

    private static DecodedArg tryFixedArrayShape(int slots) {
        var type = "bytes32[" + slots + "]";
        if (AbiTypeSyntax.staticSlotCount(type) == slots) {
            return new DecodedArg.PrimArray("[" + slots + "]", List.of("bytes32"),
                slots + "-slot fixed array (partition heuristic)");
        }
        return null;
    }

    private static List<int[]> enumeratePartitions(int total, int parts) {
        var out = new ArrayList<int[]>();
        enumeratePartitions(total, parts, 1, new int[parts], 0, out);
        if (out.size() > MAX_PARTITION_ENUMERATIONS) {
            return out.subList(0, MAX_PARTITION_ENUMERATIONS);
        }
        return out;
    }

    private static void enumeratePartitions(int remaining, int partsLeft, int minPart, int[] current,
        int index, List<int[]> out) {
        if (partsLeft == 1) {
            if (remaining >= minPart && out.size() < MAX_PARTITION_ENUMERATIONS) {
                current[index] = remaining;
                out.add(Arrays.copyOf(current, current.length));
            }
            return;
        }
        var maxFirst = remaining - (partsLeft - 1) * minPart;
        for (var first = minPart; first <= maxFirst; first++) {
            current[index] = first;
            enumeratePartitions(remaining - first, partsLeft - 1, minPart, current, index + 1, out);
            if (out.size() >= MAX_PARTITION_ENUMERATIONS) {
                return;
            }
        }
    }
}
