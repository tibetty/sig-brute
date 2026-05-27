package me.tibetty.sigbrute.decode.skeleton;

import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.DecodedArg;
import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;

/**
 * Skeleton-guided top-level decode using Etherscan / 4byte type hints. Orchestrates
 * {@link SkeletonLayout}, {@link SkeletonInlineTupleDecoder}, and {@link SkeletonArrayDecoder}.
 */
public final class SkeletonDecoder {

    private SkeletonDecoder() {
    }

    public static List<DecodedArg> decode(DecodeContext ctx, byte[] body, List<String> hint) {
        var head = SkeletonLayout.HeadSection.of(body);
        var demand = SkeletonLayout.computeSlotDemand(hint);
        SkeletonLayout.validateSlotDemand(hint, demand, head.totalSlots());

        var tupleSlots = SkeletonLayout.resolveTupleSlots(body, hint, demand, head);
        SkeletonLayout.absorbRemainingSlack(body, hint, demand.minSlots(), tupleSlots, head);
        var layout = SkeletonLayout.build(body, hint, demand.minSlots(), tupleSlots, head);
        return decodeSkeletonArgs(ctx, body, hint, demand.minSlots(), tupleSlots, layout, head);
    }

    static List<DecodedArg> decodeSkeletonArgs(DecodeContext ctx, byte[] body, List<String> hint,
        int[] minSlots, int[] tupleSlots, SkeletonLayout.Plan layout,
        SkeletonLayout.HeadSection head) {
        var out = new ArrayList<DecodedArg>(hint.size());
        for (var i = 0; i < hint.size(); i++) {
            out.add(decodeSkeletonArg(ctx, body, hint, i, minSlots, tupleSlots, layout, head));
        }
        return out;
    }

    static DecodedArg decodeSkeletonArg(DecodeContext ctx, byte[] body, List<String> hint, int argIndex,
        int[] minSlots, int[] tupleSlots, SkeletonLayout.Plan layout,
        SkeletonLayout.HeadSection head) {
        var type = hint.get(argIndex);
        var take = SkeletonLayout.slotsForHint(type, minSlots[argIndex], tupleSlots[argIndex]);
        var slotStart = layout.argHeadSlot()[argIndex];

        return switch (SkeletonTypes.kind(type)) {
            case DYNAMIC_SCALAR, DYNAMIC_PRIM_ARRAY, INLINE_TUPLE_ARRAY -> decodeSkeletonDynamicArg(
                ctx, body, type, slotStart, argIndex, layout.dynOffsets(), head.headSize());
            case OPAQUE_TUPLE -> decodeSkeletonTupleArg(ctx, body, take, slotStart, argIndex,
                layout.dynOffsets(), head);
            case STATIC -> SkeletonTypes.isInlineTuple(type)
                ? decodeInlineTupleSkeletonArg(ctx, body, type, take, slotStart, argIndex,
                    layout.dynOffsets(), head)
                : decodeStaticTypedSlots(type, body, slotStart);
        };
    }

    static DecodedArg decodeSkeletonDynamicArg(DecodeContext ctx, byte[] body, String type,
        int slotStart, int argIndex, List<int[]> dynOffsets, int headSize) {
        var offWord = AbiCodec.uintOf(AbiCodec.slice(body, slotStart * 32, 32));
        if (!AbiCodec.isPlausibleOffset(offWord, body.length, headSize)) {
            return decodeStaticTypedSlots(type, body, slotStart);
        }
        var off = AbiCodec.safeToInt(offWord, "head offset for arg " + argIndex);
        var next = AbiCodec.nextDynOffsetAfter(dynOffsets, off, body.length);
        return SkeletonArrayDecoder.decodeTopLevel(ctx, type, AbiCodec.slice(body, off, next - off));
    }

    static DecodedArg decodeSkeletonTupleArg(DecodeContext ctx, byte[] body, int take, int slotStart,
        int argIndex, List<int[]> dynOffsets, SkeletonLayout.HeadSection head) {
        if (take == 1 && AbiCodec.looksLikeOffsetAt(body, slotStart, body.length, head.headSize())) {
            var off = AbiCodec.safeToInt(AbiCodec.uintOf(AbiCodec.slice(body, slotStart * 32, 32)),
                "head offset for tuple arg " + argIndex);
            var next = AbiCodec.nextDynOffsetAfter(dynOffsets, off, body.length);
            var tail = AbiCodec.slice(body, off, next - off);
            return new DecodedArg.Tuple("", ctx.decodeBody(tail),
                "dynamic tuple at offset " + off);
        }

        var fields = new ArrayList<DecodedArg>(take);
        for (var j = 0; j < take; j++) {
            var w = AbiCodec.slice(body, (slotStart + j) * 32, 32);
            fields.add(new DecodedArg.Leaf(ctx.inferStatic(w),
                "field " + j + " — " + TypeInferrer.shapeLabel(w) + " " + TypeInferrer.hexOf(w)));
        }
        return new DecodedArg.Tuple("", fields,
            "static tuple, " + take + " field(s) (inferred from head occupancy)");
    }

    static DecodedArg decodeInlineTupleSkeletonArg(DecodeContext ctx, byte[] body, String inlineTuple,
        int take, int slotStart, int argIndex, List<int[]> dynOffsets, SkeletonLayout.HeadSection head) {
        var fieldHints = SkeletonTypes.inlineFieldTypes(inlineTuple);
        if (take == 1 && AbiCodec.looksLikeOffsetAt(body, slotStart, body.length, head.headSize())) {
            var off = AbiCodec.safeToInt(AbiCodec.uintOf(AbiCodec.slice(body, slotStart * 32, 32)),
                "head offset for inline tuple arg " + argIndex);
            var next = AbiCodec.nextDynOffsetAfter(dynOffsets, off, body.length);
            var tail = AbiCodec.slice(body, off, next - off);
            return new DecodedArg.Tuple("",
                SkeletonInlineTupleDecoder.decodeWithFieldHints(ctx, tail, fieldHints),
                "dynamic inline tuple at offset " + off);
        }

        return new DecodedArg.Tuple("",
            SkeletonInlineTupleDecoder.decodeStaticFields(ctx, body, slotStart, fieldHints),
            "static inline tuple (" + fieldHints.size() + " field(s) from skeleton)");
    }

    static DecodedArg decodeStaticTypedSlots(String type, byte[] body, int slotStart) {
        if (AbiTypeSyntax.isStaticPrimitive(type)) {
            var w = AbiCodec.slice(body, slotStart * 32, 32);
            return new DecodedArg.Leaf(List.of(type),
                TypeInferrer.shapeLabel(w) + " " + TypeInferrer.hexOf(w));
        }

        var parts = AbiTypeSyntax.splitArraySuffix(type);
        if (parts != null && !parts.suffix().contains("[]")) {
            var n = AbiTypeSyntax.staticSlotCount(type);
            var values = new StringBuilder();
            for (var i = 0; i < Math.min(n, 4); i++) {
                if (i > 0) {
                    values.append(", ");
                }
                values.append(TypeInferrer.hexOf(AbiCodec.slice(body, (slotStart + i) * 32, 32)));
            }
            if (n > 4) {
                values.append(", …");
            }
            return new DecodedArg.PrimArray(parts.suffix(), List.of(parts.base()),
                n + "-slot fixed array (" + values + ")");
        }
        var arrayShape = SkeletonShape.skeletonArrayFallback(type);
        if (arrayShape != null) {
            return arrayShape;
        }
        var w = AbiCodec.slice(body, slotStart * 32, 32);
        return new DecodedArg.Leaf(List.of(type), TypeInferrer.hexOf(w));
    }
}
