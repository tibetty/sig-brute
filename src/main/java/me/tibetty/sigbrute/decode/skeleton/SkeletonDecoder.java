package me.tibetty.sigbrute.decode.skeleton;

import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.DecodedArg;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import me.tibetty.sigbrute.decode.ShallowSkeletonHints;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;
import me.tibetty.sigbrute.decode.strategy.body.GreedyBodyDecoder;

/**
 * Skeleton-guided top-level decode using Etherscan / 4byte type hints. Orchestrates
 * {@link SkeletonLayout}, {@link SkeletonInlineTupleDecoder}, and {@link SkeletonArrayDecoder}.
 */
public final class SkeletonDecoder {

    /** Shared layout and hints for decoding one skeleton-guided argument list. */
    record SkeletonArgContext(
        DecodeContext ctx,
        byte[] body,
        List<String> hint,
        List<String> inlineHint,
        int[] minSlots,
        int[] tupleSlots,
        SkeletonLayout.Plan layout,
        SkeletonLayout.HeadSection head
    ) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SkeletonArgContext other)) {
                return false;
            }
            return Objects.equals(ctx, other.ctx)
                && Arrays.equals(body, other.body)
                && Objects.equals(hint, other.hint)
                && Objects.equals(inlineHint, other.inlineHint)
                && Arrays.equals(minSlots, other.minSlots)
                && Arrays.equals(tupleSlots, other.tupleSlots)
                && Objects.equals(layout, other.layout)
                && Objects.equals(head, other.head);
        }

        @Override
        public int hashCode() {
            var result = Objects.hash(ctx, hint, inlineHint, layout, head);
            result = 31 * result + Arrays.hashCode(body);
            result = 31 * result + Arrays.hashCode(minSlots);
            result = 31 * result + Arrays.hashCode(tupleSlots);
            return result;
        }

        @Override
        public String toString() {
            return "SkeletonArgContext[ctx=" + ctx
                + ", body=" + Arrays.toString(body)
                + ", hint=" + hint
                + ", inlineHint=" + inlineHint
                + ", minSlots=" + Arrays.toString(minSlots)
                + ", tupleSlots=" + Arrays.toString(tupleSlots)
                + ", layout=" + layout
                + ", head=" + head
                + "]";
        }
    }

    private SkeletonDecoder() {
    }

    public static List<DecodedArg> decode(DecodeContext ctx, byte[] body, List<String> hint) {
        return decode(ctx, body, hint, List.of());
    }

    public static List<DecodedArg> decode(DecodeContext ctx, byte[] body, List<String> hint,
        List<String> inlineHint) {
        var layoutHint = ShallowSkeletonHints.layoutHints(hint, inlineHint);
        var head = SkeletonLayout.HeadSection.of(body);
        var demand = SkeletonLayout.computeSlotDemand(layoutHint);
        SkeletonLayout.validateSlotDemand(layoutHint, demand, head.totalSlots());

        var tupleSlots = SkeletonLayout.resolveTupleSlots(body, layoutHint, demand, head);
        SkeletonLayout.absorbRemainingSlack(body, layoutHint, demand.minSlots(), tupleSlots, head);
        var layout = SkeletonLayout.build(body, layoutHint, demand.minSlots(), tupleSlots, head);
        var argContext = new SkeletonArgContext(ctx, body, hint, inlineHint, demand.minSlots(),
            tupleSlots, layout, head);
        return decodeSkeletonArgs(argContext);
    }

    static List<DecodedArg> decodeSkeletonArgs(SkeletonArgContext argContext) {
        var out = new ArrayList<DecodedArg>(argContext.hint().size());
        for (var i = 0; i < argContext.hint().size(); i++) {
            out.add(decodeSkeletonArg(argContext, i));
        }
        return out;
    }

    static DecodedArg decodeSkeletonArg(SkeletonArgContext argContext, int argIndex) {
        var decodeAs = ShallowSkeletonHints.bodyDecodeType(argContext.hint(),
            argContext.inlineHint(), argIndex);
        var take = SkeletonLayout.slotsForHint(decodeAs, argContext.minSlots()[argIndex],
            argContext.tupleSlots()[argIndex]);
        var slotStart = argContext.layout().argHeadSlot()[argIndex];

        return switch (SkeletonTypes.kind(decodeAs)) {
            case DYNAMIC_SCALAR, DYNAMIC_PRIM_ARRAY, INLINE_TUPLE_ARRAY -> decodeSkeletonDynamicArg(
                argContext.ctx(), argContext.body(), decodeAs, slotStart, argIndex,
                argContext.layout().dynOffsets(), argContext.head().headSize());
            case OPAQUE_TUPLE -> decodeSkeletonTupleArg(argContext.ctx(), argContext.body(), take,
                slotStart, argIndex, argContext.layout().dynOffsets(), argContext.head());
            case STATIC -> SkeletonTypes.isInlineTuple(decodeAs)
                ? decodeInlineTupleSkeletonArg(argContext, decodeAs, take, slotStart, argIndex)
                : decodeStaticTypedSlots(decodeAs, argContext.body(), slotStart);
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
                "dynamic opaque tuple at offset " + off);
        }

        if (take > 1) {
            var span = AbiCodec.slice(body, slotStart * 32, take * 32);
            var inner = GreedyBodyDecoder.decodeTupleElementBody(ctx, span);
            if (inner instanceof DecodedArg.Tuple t && !t.fields().isEmpty()) {
                return new DecodedArg.Tuple("", t.fields(),
                    "static opaque tuple, " + t.fields().size() + " field(s) (heuristic span)");
            }
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

    static DecodedArg decodeInlineTupleSkeletonArg(SkeletonArgContext argContext, String inlineTuple,
        int take, int slotStart, int argIndex) {
        var fieldHints = SkeletonTypes.inlineFieldTypes(inlineTuple);
        if (take == 1 && AbiCodec.looksLikeOffsetAt(argContext.body(), slotStart,
            argContext.body().length, argContext.head().headSize())) {
            var off = AbiCodec.safeToInt(AbiCodec.uintOf(
                AbiCodec.slice(argContext.body(), slotStart * 32, 32)),
                "head offset for inline tuple arg " + argIndex);
            var next = AbiCodec.nextDynOffsetAfter(argContext.layout().dynOffsets(), off,
                argContext.body().length);
            var tail = AbiCodec.slice(argContext.body(), off, next - off);
            return new DecodedArg.Tuple("",
                SkeletonInlineTupleDecoder.decodeWithFieldHints(argContext.ctx(), tail, fieldHints),
                "dynamic inline tuple at offset " + off);
        }

        return new DecodedArg.Tuple("",
            SkeletonInlineTupleDecoder.decodeStaticFields(argContext.ctx(), argContext.body(),
                slotStart, fieldHints),
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
