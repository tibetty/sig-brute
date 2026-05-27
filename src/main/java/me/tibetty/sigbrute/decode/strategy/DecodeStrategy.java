package me.tibetty.sigbrute.decode.strategy;

import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.DecodeResult;
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

    public static DecodeContext newContext(DecodeStrategy strategy, boolean wideCandidates) {
        if (strategy == GREEDY) {
            return DecodeContext.greedy();
        }
        return wideCandidates ? DecodeContext.heuristicSearchWide() : DecodeContext.heuristicSearch();
    }

    public static DecodeResult decode(byte[] body, List<String> topLevelHint, DecodeStrategy strategy) {
        return decode(body, topLevelHint, strategy, false);
    }

    public static DecodeResult decode(byte[] body, List<String> topLevelHint, DecodeStrategy strategy,
        boolean wideCandidates) {
        var ctx = newContext(strategy, wideCandidates);
        return strategy.decode(ctx, body, topLevelHint);
    }

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
