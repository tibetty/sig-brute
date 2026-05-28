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
        // Shallow hint names drive decode kind; inline types refine layout and opaque tuple bodies.
        var decodeAs = argContext.hint().get(argIndex);
        var take = SkeletonLayout.slotsForHint(decodeAs, argContext.minSlots()[argIndex],
            argContext.tupleSlots()[argIndex]);
        var slotStart = argContext.layout().argHeadSlot()[argIndex];

        return switch (SkeletonTypes.kind(decodeAs)) {
            case DYNAMIC_SCALAR, DYNAMIC_PRIM_ARRAY, INLINE_TUPLE_ARRAY -> decodeSkeletonDynamicArg(
                argContext, decodeAs, slotStart, argIndex);
            case OPAQUE_TUPLE -> decodeSkeletonTupleArg(argContext, take, slotStart, argIndex);
            case STATIC -> SkeletonTypes.isInlineTuple(decodeAs)
                ? decodeInlineTupleSkeletonArg(argContext, decodeAs, take, slotStart, argIndex)
                : decodeStaticTypedSlots(decodeAs, argContext.body(), slotStart);
        };
    }

    static DecodedArg decodeSkeletonDynamicArg(SkeletonArgContext argContext, String decodeAs,
        int slotStart, int argIndex) {
        var ctx = argContext.ctx();
        var body = argContext.body();
        var dynOffsets = argContext.layout().dynOffsets();
        var headBound = SkeletonLayout.headBoundForSlot(slotStart, argContext.head().headSize());
        var offWord = AbiCodec.uintOf(AbiCodec.slice(body, slotStart * 32, 32));
        if (!AbiCodec.isPlausibleOffset(offWord, body.length, headBound)) {
            return decodeStaticTypedSlots(decodeAs, body, slotStart);
        }
        var off = AbiCodec.safeToInt(offWord, "head offset for arg " + argIndex);
        var next = AbiCodec.nextDynOffsetAfter(dynOffsets, off, body.length);
        var type = ShallowSkeletonHints.dynamicDecodeType(argContext.inlineHint(), argIndex, decodeAs);
        return SkeletonArrayDecoder.decodeTopLevel(ctx, type, AbiCodec.slice(body, off, next - off));
    }

    static DecodedArg decodeSkeletonTupleArg(SkeletonArgContext argContext, int take, int slotStart,
        int argIndex) {
        var ctx = argContext.ctx();
        var body = argContext.body();
        var headBound = SkeletonLayout.headBoundForSlot(slotStart, argContext.head().headSize());
        var fieldHints = ShallowSkeletonHints.opaqueTupleFieldHints(argContext.inlineHint(), argIndex);

        var fixedArrayField = ShallowSkeletonHints.singletonFixedArrayField(fieldHints);
        if (fixedArrayField != null && AbiTypeSyntax.staticSlotCount(fixedArrayField) == take) {
            return opaqueTupleWithFields(List.of(decodeStaticTypedSlots(fixedArrayField, body, slotStart)),
                "opaque tuple, fixed array field " + fixedArrayField + " (" + take + " slots)");
        }

        if (AbiCodec.looksLikeOffsetAt(body, slotStart, body.length, headBound)) {
            return decodeDynamicOpaqueTupleAtHead(ctx, argContext, slotStart, argIndex, fieldHints);
        }

        if (!fieldHints.isEmpty() && take > 1) {
            return opaqueTupleWithFields(
                SkeletonInlineTupleDecoder.decodeStaticFields(ctx, body, slotStart, fieldHints),
                "static opaque tuple, " + fieldHints.size() + " field(s) from inline hints");
        }

        if (take > 1) {
            var decoded = tryHeuristicOpaqueTupleSpan(ctx, body, slotStart, take);
            if (decoded != null) {
                return decoded;
            }
        }

        return opaqueTupleFromOccupiedSlots(ctx, body, slotStart, take);
    }

    private static DecodedArg decodeDynamicOpaqueTupleAtHead(DecodeContext ctx,
        SkeletonArgContext argContext, int slotStart, int argIndex, List<String> fieldHints) {
        var body = argContext.body();
        var off = AbiCodec.safeToInt(AbiCodec.uintOf(AbiCodec.slice(body, slotStart * 32, 32)),
            "head offset for tuple arg " + argIndex);
        var next = AbiCodec.nextDynOffsetAfter(argContext.layout().dynOffsets(), off, body.length);
        var tail = AbiCodec.slice(body, off, next - off);
        return opaqueTupleWithFields(decodeOpaqueTupleTail(ctx, tail, fieldHints),
            "dynamic opaque tuple at offset " + off);
    }

    private static DecodedArg tryHeuristicOpaqueTupleSpan(DecodeContext ctx, byte[] body, int slotStart,
        int take) {
        var span = AbiCodec.slice(body, slotStart * 32, take * 32);
        var inner = GreedyBodyDecoder.decodeTupleElementBody(ctx, span);
        if (inner instanceof DecodedArg.Tuple t && !t.fields().isEmpty()) {
            return opaqueTupleWithFields(t.fields(),
                "static opaque tuple, " + t.fields().size() + " field(s) (heuristic span)");
        }
        return null;
    }

    private static DecodedArg opaqueTupleFromOccupiedSlots(DecodeContext ctx, byte[] body,
        int slotStart, int take) {
        var fields = new ArrayList<DecodedArg>(take);
        for (var j = 0; j < take; j++) {
            var w = AbiCodec.slice(body, (slotStart + j) * 32, 32);
            fields.add(new DecodedArg.Leaf(ctx.inferStatic(w),
                "field " + j + " — " + TypeInferrer.shapeLabel(w) + " " + TypeInferrer.hexOf(w)));
        }
        return opaqueTupleWithFields(fields,
            "static tuple, " + take + " field(s) (inferred from head occupancy)");
    }

    private static DecodedArg opaqueTupleWithFields(List<DecodedArg> fields, String comment) {
        return new DecodedArg.Tuple("", fields, comment);
    }

    static DecodedArg decodeInlineTupleSkeletonArg(SkeletonArgContext argContext, String inlineTuple,
        int take, int slotStart, int argIndex) {
        var fieldHints = SkeletonTypes.inlineFieldTypes(inlineTuple);
        var headBound = SkeletonLayout.headBoundForSlot(slotStart, argContext.head().headSize());
        if (take == 1 && AbiCodec.looksLikeOffsetAt(argContext.body(), slotStart,
            argContext.body().length, headBound)) {
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

    /**
     * Head/tail tuple body decode for opaque {@code tuple}. Uses inline field hints when provided
     * (structure from {@code Function:}); otherwise heuristic {@link GreedyBodyDecoder}.
     */
    static List<DecodedArg> decodeOpaqueTupleTail(DecodeContext ctx, byte[] tail,
        List<String> fieldHints) {
        if (!fieldHints.isEmpty()) {
            return SkeletonInlineTupleDecoder.decodeWithFieldHints(ctx, tail, fieldHints);
        }
        return GreedyBodyDecoder.decodeBestHeadTailTuple(ctx, tail);
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
