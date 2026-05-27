package me.tibetty.sigbrute.decode.strategy.body;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.abi.AbiCodec;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.decode.layout.DynamicHeadSlots;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;

/**
 * Heuristic-search tuple-body decoder: at each step tries static words, head/tail layouts, and
 * multiple dynamic-tail interpretations, keeping branches that fully consume the slice and picking
 * the highest-scoring parse. Near-tie branches with the same structure merge leaf candidates into
 * the emitted YAML; structurally different near-ties are recorded as warnings.
 */
public final class SearchBodyDecoder {

    private static final int MAX_DYNAMIC_BRANCHES = 8;
    /** Score gap within which an alternate parse may contribute extra type candidates or a warning. */
    private static final int NEAR_TIE_SCORE_DELTA = 30;

    private SearchBodyDecoder() {
    }

    public static List<DecodedArg> decode(DecodeContext ctx, byte[] body) {
        var result = searchFields(ctx, body);
        if (result == null) {
            return List.of();
        }
        return result.fields();
    }

    public static DecodedArg decodeDynamicField(DecodeContext ctx, byte[] body) {
        var branches = collectDynamicBranches(ctx, body);
        if (branches.isEmpty()) {
            return fallbackTuple(ctx, body);
        }
        var best = pickBest(branches);
        if (best == null) {
            return fallbackTuple(ctx, body);
        }
        return mergeNearTieDynamicArg(ctx, best, branches);
    }

    /** Package-private for tests. */
    static DecodedArg mergeArgsForTests(DecodedArg a, DecodedArg b) {
        return mergeArg(a, b);
    }

    private record ParseResult(List<DecodedArg> fields, DecodedArg arg, int score, String tag) {
    }

    private static ParseResult searchFields(DecodeContext ctx, byte[] body) {
        if (body.length == 0) {
            return new ParseResult(List.of(), null, 1000, "empty");
        }
        if ((body.length & 31) != 0) {
            return null;
        }

        var candidates = new ArrayList<ParseResult>();
        var headSizes = headSizeCandidates(body);
        if (headSizes.isEmpty()) {
            candidates.add(tryFlatStatic(ctx, body));
        } else {
            for (var headSize : headSizes) {
                var headTail = tryHeadTail(ctx, body, headSize);
                if (headTail != null) {
                    candidates.add(headTail);
                }
            }
        }

        var best = pickBest(candidates);
        if (best != null) {
            reportFieldParseAlternates(ctx, best, candidates);
        }
        return best;
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

    private static ParseResult tryFlatStatic(DecodeContext ctx, byte[] body) {
        var numWords = body.length / 32;
        var fields = new ArrayList<DecodedArg>(numWords);
        for (var i = 0; i < numWords; i++) {
            fields.add(staticWordLeaf(ctx, AbiCodec.slice(body, i * 32, 32)));
        }
        return new ParseResult(fields, null, scoreFlatStatic(numWords), "flat");
    }

    private static int scoreFlatStatic(int numWords) {
        return 100 + numWords;
    }

    private static ParseResult tryHeadTail(DecodeContext ctx, byte[] body, int headSize) {
        var numFields = headSize / 32;
        var dynSlots = DynamicHeadSlots.collect(body, numFields, headSize);
        var fields = new ArrayList<DecodedArg>(numFields);
        for (var i = 0; i < numFields; i++) {
            var word = AbiCodec.slice(body, i * 32, 32);
            fields.add(decodeOneHeadField(ctx, body, i, word, headSize, dynSlots));
        }
        var score = 500 + dynSlots.size() * 20 - (body.length / 32 - numFields);
        return new ParseResult(fields, null, score, "head:" + headSize);
    }

    private static DecodedArg decodeOneHeadField(DecodeContext ctx, byte[] body, int fieldIndex, byte[] word,
        int headSize, List<int[]> dynSlots) {
        var value = AbiCodec.uintOf(word);
        if (!AbiCodec.isPlausibleOffset(value, body.length, headSize)) {
            return staticWordLeaf(ctx, word);
        }

        var offset = AbiCodec.safeToInt(value, "field offset [" + fieldIndex + "]");
        var next = AbiCodec.nextDynOffsetAfter(dynSlots, offset, body.length);
        return decodeDynamicField(ctx, AbiCodec.slice(body, offset, next - offset));
    }

    private static DecodedArg.Leaf staticWordLeaf(DecodeContext ctx, byte[] word) {
        return new DecodedArg.Leaf(ctx.inferStatic(word),
            TypeInferrer.shapeLabel(word) + " " + TypeInferrer.hexOf(word));
    }

    private static List<ParseResult> collectDynamicBranches(DecodeContext ctx, byte[] body) {
        var branches = new ArrayList<ParseResult>();
        if (body.length < 32) {
            branches.add(new ParseResult(null,
                new DecodedArg.Leaf(List.of(AbiTypeSyntax.BYTES), "0 bytes payload"), 50, "bytes0"));
            return branches;
        }

        var lengthWord = AbiCodec.uintOf(AbiCodec.slice(body, 0, 32));
        addBranch(branches, GreedyBodyDecoder.tryDecodeLengthPrefixedBytes(body, lengthWord), 400,
            "bytes");
        addBranch(branches, GreedyBodyDecoder.tryDecodeArrayFromLengthPrefix(ctx, body, lengthWord),
            350,
            "array");
        addBranch(branches, tryNestedTuple(ctx, body), 200, "tuple");

        return branches.size() > MAX_DYNAMIC_BRANCHES
            ? branches.subList(0, MAX_DYNAMIC_BRANCHES)
            : branches;
    }

    private static void addBranch(List<ParseResult> branches, DecodedArg arg, int baseScore,
        String tag) {
        if (arg != null) {
            var penalty = arg.comment() != null && arg.comment().contains("fallback") ? 50 : 0;
            branches.add(new ParseResult(null, arg, baseScore - penalty, tag));
        }
    }

    private static DecodedArg tryNestedTuple(DecodeContext ctx, byte[] body) {
        var inner = searchFields(ctx, body);
        if (inner == null || inner.fields().isEmpty()) {
            return null;
        }
        return new DecodedArg.Tuple("", inner.fields(),
            "search tuple (" + inner.fields().size() + " field(s))");
    }

    private static DecodedArg fallbackTuple(DecodeContext ctx, byte[] body) {
        var inner = searchFields(ctx, body);
        if (inner != null && !inner.fields().isEmpty()) {
            ctx.warn("heuristic search used fallback tuple parse for dynamic tail");
            return new DecodedArg.Tuple("", inner.fields(),
                "fallback search tuple (could also be bytes/string or T[])");
        }
        return new DecodedArg.Tuple("",
            GreedyBodyDecoder.decodeFlatStaticWords(ctx, body, body.length / 32),
            "fallback flat tuple");
    }

    /** Highest score; on a tie, earliest candidate in insertion order (stable, deterministic). */
    private static ParseResult pickBest(List<ParseResult> candidates) {
        ParseResult best = null;
        var bestIndex = Integer.MAX_VALUE;
        for (var i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.score() > best.score()
                || (candidate.score() == best.score() && i < bestIndex)) {
                best = candidate;
                bestIndex = i;
            }
        }
        return best;
    }

    private static void reportFieldParseAlternates(DecodeContext ctx, ParseResult best,
        List<ParseResult> candidates) {
        var bestStruct = SignatureStructure.fromDecodedArgs(best.fields());
        for (var other : candidates) {
            if (other == null || other == best) {
                continue;
            }
            if (other.score() < best.score() - NEAR_TIE_SCORE_DELTA) {
                continue;
            }
            if (other.fields() == null) {
                continue;
            }
            if (!bestStruct.structureEquals(SignatureStructure.fromDecodedArgs(other.fields()))) {
                ctx.warn("alternate body parse [" + other.tag() + "] score " + other.score()
                    + " (winner " + best.tag() + " " + best.score() + ")");
                ctx.recordAlternate(other.fields(), other.tag());
            }
        }
    }

    private static DecodedArg mergeNearTieDynamicArg(DecodeContext ctx, ParseResult best,
        List<ParseResult> branches) {
        var merged = best.arg();
        var winnerStruct = SignatureStructure.fromDecodedArgs(List.of(merged));
        for (var other : branches) {
            if (other == best) {
                continue;
            }
            if (other.score() < best.score() - NEAR_TIE_SCORE_DELTA) {
                continue;
            }
            if (other.arg() == null) {
                continue;
            }
            if (!winnerStruct.structureEquals(SignatureStructure.fromDecodedArgs(List.of(other.arg())))) {
                ctx.warn("alternate dynamic parse [" + other.tag() + "] score " + other.score()
                    + " (winner " + best.tag() + " " + best.score() + ")");
                ctx.recordAlternate(List.of(other.arg()), other.tag());
                continue;
            }
            merged = mergeArg(merged, other.arg());
        }
        return merged;
    }

    private static List<DecodedArg> mergeFieldLists(List<DecodedArg> primary, List<DecodedArg> secondary) {
        if (primary.size() != secondary.size()) {
            return primary;
        }
        var out = new ArrayList<DecodedArg>(primary.size());
        for (var i = 0; i < primary.size(); i++) {
            out.add(mergeArg(primary.get(i), secondary.get(i)));
        }
        return out;
    }

    private static DecodedArg mergeArg(DecodedArg primary, DecodedArg secondary) {
        if (primary instanceof DecodedArg.Leaf leaf && secondary instanceof DecodedArg.Leaf other) {
            return new DecodedArg.Leaf(unionCandidates(leaf.candidates(), other.candidates()),
                leaf.comment());
        }
        if (primary instanceof DecodedArg.PrimArray array
            && secondary instanceof DecodedArg.PrimArray otherArray) {
            if (!array.arraySuffix().equals(otherArray.arraySuffix())) {
                return primary;
            }
            return new DecodedArg.PrimArray(array.arraySuffix(),
                unionCandidates(array.baseCandidates(), otherArray.baseCandidates()), array.comment());
        }
        if (primary instanceof DecodedArg.Tuple tuple && secondary instanceof DecodedArg.Tuple otherTuple) {
            if (!tuple.arraySuffix().equals(otherTuple.arraySuffix())) {
                return primary;
            }
            return new DecodedArg.Tuple(tuple.arraySuffix(),
                mergeFieldLists(tuple.fields(), otherTuple.fields()), tuple.comment());
        }
        return primary;
    }

    private static List<String> unionCandidates(List<String> a, List<String> b) {
        var set = new LinkedHashSet<String>();
        set.addAll(a);
        set.addAll(b);
        return List.copyOf(set);
    }
}
