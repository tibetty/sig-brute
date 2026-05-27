package me.tibetty.sigbrute.decode;

import java.util.List;
import me.tibetty.sigbrute.decode.abi.UintPatternCompactor;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;

/**
 * Calldata decoder entry point. Delegates to {@link DecodeStrategy}: greedy
 * (first-fit structure) or heuristic search (explores alternate parses).
 *
 * @see package-info
 */
public final class AbiDecoder {

    private AbiDecoder() {
    }

    public static List<DecodedArg> decodeArgs(byte[] body, List<String> topLevelHint) {
        return decodeArgs(body, topLevelHint, DecodeStrategy.GREEDY);
    }

    public static List<DecodedArg> decodeArgs(byte[] body, List<String> topLevelHint,
        DecodeStrategy strategy) {
        return decodeResult(body, topLevelHint, strategy).args();
    }

    public static DecodeResult decodeResult(byte[] body, List<String> topLevelHint,
        DecodeStrategy strategy) {
        return decodeResult(body, topLevelHint, strategy, false);
    }

    public static DecodeResult decodeResult(byte[] body, List<String> topLevelHint,
        DecodeStrategy strategy, boolean wideCandidates) {
        return decodeResult(body, topLevelHint, strategy, wideCandidates, List.of());
    }

    public static DecodeResult decodeResult(byte[] body, List<String> topLevelHint,
        DecodeStrategy strategy, boolean wideCandidates, List<String> inlineTopLevelHint) {
        if ((body.length & 31) != 0) {
            throw new IllegalArgumentException("body length not a multiple of 32: " + body.length);
        }

        return DecodeStrategy.decode(body, hintOrEmpty(topLevelHint), strategy, wideCandidates,
            hintOrEmpty(inlineTopLevelHint));
    }

    private static List<String> hintOrEmpty(List<String> hint) {
        return hint == null ? List.of() : hint;
    }

    /**
     * Collapses consecutive uint runs in candidate lists (used when merging array element types).
     */
    public static List<String> compactUintFloor(List<String> types) {
        return UintPatternCompactor.compact(types);
    }
}
