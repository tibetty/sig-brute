package me.tibetty.sigbrute.decode.skeleton;

import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.DecodedArg;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;

/** Hint-guided decode of inline tuple fields (static head and dynamic tail). */
public final class SkeletonInlineTupleDecoder {

    private SkeletonInlineTupleDecoder() {
    }

    static List<DecodedArg> decodeStaticFields(DecodeContext ctx, byte[] body, int slotStart,
        List<String> fieldHints) {
        var byteOff = slotStart * 32;
        var slice = AbiCodec.slice(body, byteOff, body.length - byteOff);
        var headSize = AbiCodec.scanHeadSize(slice);
        if (headSize >= 0) {
            headSize += byteOff;
        } else {
            var headSlots = 0;
            for (var hint : fieldHints) {
                headSlots += headSlotsForFieldHint(hint);
            }
            headSize = (slotStart + headSlots) * 32;
        }

        var dynSlots = assignDynamicOffsetsInOrder(body, fieldHints, headSize, slotStart,
            slotStart * 32);
        var fields = new ArrayList<DecodedArg>(fieldHints.size());
        var cursor = slotStart;
        for (var i = 0; i < fieldHints.size(); i++) {
            fields.add(decodeStaticFieldAt(ctx, body, fieldHints, i, cursor, headSize, dynSlots));
            cursor += headSlotsForFieldHint(fieldHints.get(i));
        }
        return fields;
    }

    static List<DecodedArg> decodeWithFieldHints(DecodeContext ctx, byte[] body,
        List<String> fieldHints) {
        var headSize = AbiCodec.scanHeadSize(body);
        if (headSize < 0) {
            var headSlots = 0;
            for (var hint : fieldHints) {
                headSlots += headSlotsForFieldHint(hint);
            }
            headSize = headSlots * 32;
        }
        if (body.length < headSize) {
            return ctx.decodeBody(body);
        }

        var dynSlots = assignDynamicOffsetsInOrder(body, fieldHints, headSize, 0, 0);
        var fields = new ArrayList<DecodedArg>(fieldHints.size());
        var cursor = 0;
        for (var i = 0; i < fieldHints.size(); i++) {
            fields.add(decodeHintedFieldAt(ctx, body, fieldHints, i, cursor, headSize, dynSlots));
            cursor += headSlotsForFieldHint(fieldHints.get(i));
        }
        return fields;
    }

    static int staticSlotsForFieldHintList(List<String> fieldHints) {
        var total = 0;
        for (var field : fieldHints) {
            var slots = AbiTypeSyntax.staticSlotCount(field);
            if (slots == 0) {
                return 0;
            }
            total += slots;
        }
        return total;
    }

    static boolean fieldUsesDynamicHeadSlot(String hint) {
        if (AbiTypeSyntax.isDynamicHint(hint)) {
            return true;
        }
        return SkeletonTypes.isInlineTuple(hint) && AbiTypeSyntax.staticInlineTupleHeadSlots(hint) == 0;
    }

    static int headSlotsForFieldHint(String hint) {
        if (SkeletonTypes.isInlineTuple(hint)) {
            var slots = AbiTypeSyntax.staticInlineTupleHeadSlots(hint);
            return slots > 0 ? slots : 1;
        }
        return AbiTypeSyntax.staticSlotCount(hint) > 0 ? AbiTypeSyntax.staticSlotCount(hint) : 1;
    }

    static List<int[]> assignDynamicOffsetsInOrder(byte[] body, List<String> fieldHints,
        int headSize, int fromSlot, int minOffsetBytes) {
        var candidates = collectPlausibleHeadOffsets(body, headSize, fromSlot, minOffsetBytes);
        var assigned = new ArrayList<int[]>();
        var cursor = fromSlot;
        var usedOffsets = new HashSet<Integer>();
        var minByteOffset = minOffsetBytes;
        var maxSlot = headSize / 32;
        for (var hint : fieldHints) {
            if (!fieldUsesDynamicHeadSlot(hint)) {
                cursor += headSlotsForFieldHint(hint);
                continue;
            }

            var pick = pickEarliestDynamicOffset(candidates, cursor, maxSlot, minByteOffset,
                usedOffsets);
            if (pick != null) {
                assigned.add(pick);
                usedOffsets.add(pick[1]);
                minByteOffset = pick[1] + 32;
            }
            cursor += headSlotsForFieldHint(hint);
        }
        return assigned;
    }

    private static int[] pickEarliestDynamicOffset(List<int[]> candidates, int cursor,
        int maxSlot, int minByteOffset, HashSet<Integer> usedOffsets) {
        int[] pick = null;
        for (int[] cand : candidates) {
            if (isEligibleDynamicOffset(cand, cursor, maxSlot, minByteOffset, usedOffsets)
                && (pick == null || cand[0] < pick[0])) {
                pick = cand;
            }
        }
        return pick;
    }

    private static boolean isEligibleDynamicOffset(int[] cand, int cursor, int maxSlot,
        int minByteOffset, HashSet<Integer> usedOffsets) {
        return cand[0] >= cursor && cand[0] < maxSlot && cand[1] >= minByteOffset
            && !usedOffsets.contains(cand[1]);
    }

    static DecodedArg decodeStaticFieldAt(DecodeContext ctx, byte[] body, List<String> fieldHints,
        int fieldIndex, int slotStart, int headSize, List<int[]> dynSlots) {
        return decodeFieldAt(ctx, body, fieldHints, fieldIndex, slotStart, headSize, dynSlots, true);
    }

    static DecodedArg decodeHintedFieldAt(DecodeContext ctx, byte[] body, List<String> fieldHints,
        int fieldIndex, int slotStart, int headSize, List<int[]> dynSlots) {
        return decodeFieldAt(ctx, body, fieldHints, fieldIndex, slotStart, headSize, dynSlots, false);
    }

    private static DecodedArg decodeFieldAt(DecodeContext ctx, byte[] body, List<String> fieldHints,
        int fieldIndex, int slotStart, int headSize, List<int[]> dynSlots, boolean staticInline) {
        var hint = fieldHints.get(fieldIndex);
        if (SkeletonTypes.isInlineTuple(hint)) {
            var inner = SkeletonTypes.inlineFieldTypes(hint);
            var innerSlots = AbiTypeSyntax.staticInlineTupleHeadSlots(hint);
            if (innerSlots > 0) {
                return new DecodedArg.Tuple("",
                    decodeStaticFields(ctx, body, slotStart, inner),
                    "nested static inline tuple");
            }
        }
        var mapped = nthDynamicOffset(dynSlots, dynamicHintIndex(fieldHints, fieldIndex));
        if (mapped != null && fieldUsesDynamicHeadSlot(hint)) {
            var next = AbiCodec.nextDynOffsetAfter(dynSlots, mapped, body.length);
            return SkeletonArrayDecoder.decodeDynamicFieldValue(ctx, hint,
                AbiCodec.slice(body, mapped, next - mapped));
        }

        if (fieldUsesDynamicHeadSlot(hint)) {
            var word = AbiCodec.slice(body, slotStart * 32, 32);
            if (AbiCodec.isPlausibleOffset(AbiCodec.uintOf(word), body.length, headSize)) {
                var offset = AbiCodec.safeToInt(AbiCodec.uintOf(word),
                    staticInline
                        ? "inline tuple field offset [" + slotStart + "]"
                        : "hinted field offset at slot " + slotStart);
                var next = AbiCodec.nextDynOffsetAfter(dynSlots, offset, body.length);
                return SkeletonArrayDecoder.decodeDynamicFieldValue(ctx, hint,
                    AbiCodec.slice(body, offset, next - offset));
            }
        }
        var arrayShape = SkeletonShape.skeletonArrayFallback(hint);
        if (arrayShape != null) {
            return arrayShape;
        }
        return SkeletonDecoder.decodeStaticTypedSlots(hint, body, slotStart);
    }

    private static List<int[]> collectPlausibleHeadOffsets(byte[] body, int headSize, int fromSlot,
        int minOffsetBytes) {
        var dynSlots = new ArrayList<int[]>();
        var numSlots = headSize / 32;
        for (var i = fromSlot; i < numSlots; i++) {
            var value = AbiCodec.uintOf(AbiCodec.slice(body, i * 32, 32));
            if (AbiCodec.isPlausibleOffset(value, body.length, minOffsetBytes)) {
                dynSlots.add(new int[]{i,
                    AbiCodec.safeToInt(value, "head offset at slot " + i)});
            }
        }
        return dynSlots;
    }

    private static int dynamicHintIndex(List<String> fieldHints, int fieldIndex) {
        if (!fieldUsesDynamicHeadSlot(fieldHints.get(fieldIndex))) {
            return -1;
        }

        var n = 0;
        for (var i = 0; i < fieldIndex; i++) {
            if (fieldUsesDynamicHeadSlot(fieldHints.get(i))) {
                n++;
            }
        }
        return n;
    }

    private static Integer nthDynamicOffset(List<int[]> assignedDynSlots, int n) {
        if (n < 0 || n >= assignedDynSlots.size()) {
            return null;
        }

        return assignedDynSlots.get(n)[1];
    }
}
