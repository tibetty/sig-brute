package me.tibetty.sigbrute.decode.strategy;

import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.DecodeResult;
import me.tibetty.sigbrute.decode.infer.GeneralizedTypeInferrer;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.decode.infer.WideTypeInferrer;
import me.tibetty.sigbrute.decode.skeleton.SkeletonDecoder;
import me.tibetty.sigbrute.decode.strategy.body.GreedyBodyDecoder;
import me.tibetty.sigbrute.decode.strategy.body.SearchBodyDecoder;

/**
 * Calldata decode strategy. {@link #GREEDY} commits to the first structurally valid parse;
 * {@link #HEURISTIC_SEARCH} explores alternate head boundaries and dynamic-tail shapes, then picks
 * the best-scoring full consumption.
 *
 * <p>
 * When Etherscan / 4byte {@code Function:} hints are present, both strategies use
 * {@link SkeletonDecoder} at the top level; strategy only changes nested body hooks via
 * {@link DecodeContext}.
 *
 * <p>
 * This enum also owns {@link #newContext} (context factory) and the static {@link #decode}
 * helpers. Co-locating context creation here keeps it adjacent to the strategy it configures
 * and prevents {@link DecodeContext} from importing its own concrete body-decoder implementations,
 * which would otherwise create a circular {@code strategy} ↔ {@code strategy.body} dependency.
 */
public enum DecodeStrategy {

    GREEDY("greedy") {
        @Override
        List<DecodedArg> decodeArgs(DecodeContext ctx, byte[] body, List<String> topLevelHint) {
            if (topLevelHint != null && !topLevelHint.isEmpty()) {
                return SkeletonDecoder.decode(ctx, body, topLevelHint);
            }
            return GreedyBodyDecoder.decode(ctx, body);
        }
    },

    HEURISTIC_SEARCH("heuristic_search") {
        @Override
        List<DecodedArg> decodeArgs(DecodeContext ctx, byte[] body, List<String> topLevelHint) {
            if (topLevelHint != null && !topLevelHint.isEmpty()) {
                return SkeletonDecoder.decode(ctx, body, topLevelHint);
            }
            return SearchBodyDecoder.decode(ctx, body);
        }
    };

    private final String strategyId;

    DecodeStrategy(String strategyId) {
        this.strategyId = strategyId;
    }

    /** Stable CLI / config id (e.g. {@code greedy}, {@code heuristic_search}). */
    public String id() {
        return strategyId;
    }

    abstract List<DecodedArg> decodeArgs(DecodeContext ctx, byte[] body, List<String> topLevelHint);

    public DecodeResult decode(DecodeContext ctx, byte[] body, List<String> topLevelHint) {
        var args = decodeArgs(ctx, body, topLevelHint);
        return ctx.toResult(args, this);
    }

    public static DecodeContext newContext(DecodeStrategy strategy) {
        return newContext(strategy, false);
    }

    /** Creates a fresh, per-call {@link DecodeContext} wired for the given strategy. */
    public static DecodeContext newContext(DecodeStrategy strategy, boolean wideCandidates) {
        if (strategy == GREEDY) {
            return new DecodeContext(
                GreedyBodyDecoder::decode,
                GreedyBodyDecoder::decodeDynamicField,
                TypeInferrer::inferStatic,
                new ArrayList<>(),
                new ArrayList<>());
        }
        if (wideCandidates) {
            return new DecodeContext(
                SearchBodyDecoder::decode,
                SearchBodyDecoder::decodeDynamicField,
                WideTypeInferrer::inferStatic,
                new ArrayList<>(),
                new ArrayList<>());
        }
        return new DecodeContext(
            SearchBodyDecoder::decode,
            SearchBodyDecoder::decodeDynamicField,
            GeneralizedTypeInferrer::inferStatic,
            new ArrayList<>(),
            new ArrayList<>());
    }

    public static DecodeResult decode(byte[] body, List<String> topLevelHint,
        DecodeStrategy strategy) {
        return decode(body, topLevelHint, strategy, false);
    }

    public static DecodeResult decode(byte[] body, List<String> topLevelHint,
        DecodeStrategy strategy, boolean wideCandidates) {
        var ctx = newContext(strategy, wideCandidates);
        return strategy.decode(ctx, body, topLevelHint);
    }

    /**
     * Parses a strategy id string. The canonical ids are {@code "greedy"} and
     * {@code "heuristic_search"}; the aliases {@code "heuristic"} and {@code "search"} are also
     * accepted for convenience.
     *
     * <p>
     * {@link #id()} always returns the canonical id, so round-tripping via
     * {@code fromId(strategy.id())} is always safe. Aliases are intentional shortcuts and will
     * not be removed without a deprecation cycle — using {@code "heuristic"} in a stored config
     * file will continue to work.
     *
     * <p>
     * A {@code null} or blank id is treated as {@link #GREEDY} (the default strategy).
     *
     * @throws IllegalArgumentException if the id is non-null, non-blank, and unrecognised
     */
    public static DecodeStrategy fromId(String id) {
        if (id == null || id.isBlank() || "greedy".equalsIgnoreCase(id)) {
            return GREEDY;
        }
        if ("heuristic_search".equalsIgnoreCase(id) || "heuristic".equalsIgnoreCase(id)
            || "search".equalsIgnoreCase(id)) {
            return HEURISTIC_SEARCH;
        }
        throw new IllegalArgumentException(
            "unknown decode strategy: " + id + " (use greedy or heuristic_search)");
    }
}
