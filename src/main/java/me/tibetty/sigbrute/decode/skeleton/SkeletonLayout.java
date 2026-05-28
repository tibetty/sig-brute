package me.tibetty.sigbrute.decode.skeleton;

import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Head-slot planning for skeleton-guided top-level decode. */
public final class SkeletonLayout {

    private SkeletonLayout() {
    }

    record HeadSection(int headSize, int totalSlots) {
        static HeadSection of(byte[] body) {
            var headSize = AbiCodec.scanHeadSize(body);
            if (headSize < 0) {
                headSize = body.length;
            }
            return new HeadSection(headSize, headSize / 32);
        }
    }

    record SlotDemand(int[] minSlots, int demandSum, int unknownTupleCount) {
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

    record TupleSlotResolution(int slots, int cursorAdvance, int slackConsumed) {
    }

    record Plan(int[] argHeadSlot, List<int[]> dynOffsets) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof Plan other && Arrays.equals(argHeadSlot, other.argHeadSlot)
                && offsetPairsEqual(dynOffsets, other.dynOffsets);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(argHeadSlot), offsetPairsHash(dynOffsets));
        }

        @Override
        public String toString() {
            return "Plan[argHeadSlot=" + Arrays.toString(argHeadSlot) + ", dynOffsets="
                + Arrays.deepToString(dynOffsets.toArray(int[][]::new)) + ']';
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

    static SlotDemand computeSlotDemand(List<String> hint) {
        var minSlots = new int[hint.size()];
        var demandSum = 0;
        var unknownTupleCount = 0;

        for (var i = 0; i < hint.size(); i++) {
            var t = hint.get(i);
            var s = AbiTypeSyntax.staticSlotCount(t);
            if (SkeletonTypes.isTupleHint(t)) {
                if (s > 0) {
                    minSlots[i] = s;
                    demandSum += s;
                } else {
                    minSlots[i] = 1;
                    demandSum += 1;
                    unknownTupleCount++;
                }
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

    static void validateSlotDemand(List<String> hint, SlotDemand demand, int totalSlots) {
        var slack = totalSlots - demand.demandSum();
        if (slack < 0 && demand.unknownTupleCount() == 0) {
            throw new IllegalStateException("Skeleton " + hint + " demands " + demand.demandSum()
                + " head slots but body has only " + totalSlots);
        }
    }

    static int[] resolveTupleSlots(byte[] body, List<String> hint, SlotDemand demand,
        HeadSection head) {
        var tupleSlots = new int[hint.size()];
        var slack = head.totalSlots() - demand.demandSum();
        var cursor = 0;

        for (var i = 0; i < hint.size(); i++) {
            var t = hint.get(i);
            if (SkeletonTypes.isTupleHint(t)) {
                var resolution = resolveOneTupleSlot(body, hint, demand, head, slack, i, cursor);
                tupleSlots[i] = resolution.slots();
                cursor += resolution.cursorAdvance();
                slack -= resolution.slackConsumed();
            } else {
                cursor += demand.minSlots()[i];
            }
        }

        return tupleSlots;
    }

    static TupleSlotResolution resolveOneTupleSlot(byte[] body, List<String> hint, SlotDemand demand,
        HeadSection head, int slack, int index, int cursor) {
        var type = hint.get(index);
        var staticInlineSlots = AbiTypeSyntax.staticInlineTupleHeadSlots(type);
        if (staticInlineSlots > 0) {
            return new TupleSlotResolution(staticInlineSlots, staticInlineSlots, 0);
        }

        if (cursor >= head.totalSlots()) {
            return new TupleSlotResolution(1, 1, 0);
        }

        var headBound = headBoundForSlot(cursor, head.headSize());
        var dynamic = AbiCodec.looksLikeOffsetAt(body, cursor, body.length, headBound);
        if (dynamic) {
            return new TupleSlotResolution(1, 1, 0);
        }

        if (SkeletonTypes.TUPLE.equals(type)) {
            var reserved = reservedHeadSlotsAfter(hint, demand, index);
            var maxTake = Math.max(1, head.totalSlots() - cursor - reserved);
            var take = Math.min(countStaticHeadSlotsUntilOffset(body, head, cursor), maxTake);
            return new TupleSlotResolution(take, take, 0);
        }

        var take = countTuplesAfter(hint, index) == 0 ? slack + 1 : 1;
        return new TupleSlotResolution(take, take, take - 1);
    }

    /**
     * Consecutive head slots that belong to one static opaque {@code tuple} (stop at the first
     * plausible dynamic tail offset).
     */
    static int reservedHeadSlotsAfter(List<String> hint, SlotDemand demand, int fromIndex) {
        var reserved = 0;
        for (var j = fromIndex + 1; j < hint.size(); j++) {
            reserved += demand.minSlots()[j];
        }
        return reserved;
    }

    /**
     * Plausible head boundary for one argument starting at {@code slotStart}: at least one slot,
     * never wider than the global scan (which may include a nested tuple's interior offsets).
     */
    static int headBoundForSlot(int slotStart, int scannedHeadSize) {
        return Math.min(scannedHeadSize, (slotStart + 1) * 32);
    }

    static int countStaticHeadSlotsUntilOffset(byte[] body, HeadSection head, int cursor) {
        var take = 1;
        for (var slot = cursor + 1; slot < head.totalSlots(); slot++) {
            if (AbiCodec.looksLikeOffsetAt(body, slot, body.length, head.headSize())) {
                break;
            }
            take++;
        }
        return take;
    }

    static int countTuplesAfter(List<String> hint, int fromIndex) {
        var count = 0;
        for (var j = fromIndex + 1; j < hint.size(); j++) {
            if (SkeletonTypes.isTupleHint(hint.get(j))) {
                count++;
            }
        }
        return count;
    }

    static void absorbRemainingSlack(byte[] body, List<String> hint, int[] minSlots,
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

    static int findStaticTupleSlackTarget(List<String> hint, byte[] body, int[] minSlots,
        int[] tupleSlots, HeadSection head) {
        for (var i = 0; i < hint.size(); i++) {
            if (SkeletonTypes.isTupleHint(hint.get(i)) && tupleSlots[i] == 1) {
                var slotIndex = slotCursorBeforeIndex(hint, i, minSlots, tupleSlots);
                var headBound = headBoundForSlot(slotIndex, head.headSize());
                if (!AbiCodec.looksLikeOffsetAt(body, slotIndex, body.length, headBound)) {
                    return i;
                }
            }
        }
        return -1;
    }

    static int headSlotCount(List<String> hint, int[] minSlots, int[] tupleSlots) {
        var total = 0;
        for (var i = 0; i < hint.size(); i++) {
            total += SkeletonTypes.isTupleHint(hint.get(i)) ? tupleSlots[i] : minSlots[i];
        }
        return total;
    }

    static Plan build(byte[] body, List<String> hint, int[] minSlots, int[] tupleSlots,
        HeadSection head) {
        var dynOffsets = new ArrayList<int[]>();
        var argHeadSlot = new int[hint.size()];
        var sc = 0;

        for (var i = 0; i < hint.size(); i++) {
            argHeadSlot[i] = sc;
            var t = hint.get(i);
            var take = slotsForHint(t, minSlots[i], tupleSlots[i]);
            if (registersDynOffset(t, take, body, sc, head)) {
                var offWord = AbiCodec.uintOf(AbiCodec.slice(body, sc * 32, 32));
                if (AbiCodec.isPlausibleOffset(offWord, body.length, head.headSize())) {
                    dynOffsets.add(new int[]{i, AbiCodec.safeToInt(offWord, "head offset for arg " + i)});
                }
            }
            sc += take;
        }

        return new Plan(argHeadSlot, dynOffsets);
    }

    static int slotsForHint(String type, int minSlots, int tupleSlots) {
        return SkeletonTypes.isTupleHint(type) ? tupleSlots : minSlots;
    }

    static boolean registersDynOffset(String type, int take, byte[] body, int slotIndex,
        HeadSection head) {
        if (AbiTypeSyntax.isDynamicHint(type)) {
            return true;
        }
        var headBound = headBoundForSlot(slotIndex, head.headSize());
        return SkeletonTypes.isTupleHint(type) && take == 1
            && AbiCodec.looksLikeOffsetAt(body, slotIndex, body.length, headBound);
    }

    static int slotCursorBeforeIndex(List<String> hint, int idx, int[] minSlots,
        int[] tupleSlots) {
        var sc = 0;
        for (var j = 0; j < idx; j++) {
            sc += SkeletonTypes.isTupleHint(hint.get(j)) ? tupleSlots[j] : minSlots[j];
        }
        return sc;
    }
}
