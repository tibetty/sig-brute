package me.tibetty.sigbrute.decode.infer;

import java.util.List;

/**
 * Static-slot inference for the heuristic-search strategy: delegates to {@link TypeInferrer}, then
 * collapses each numeric / bytes / fixed-point family to a single floor or wildcard pattern.
 */
public final class GeneralizedTypeInferrer {

    private GeneralizedTypeInferrer() {
    }

    public static List<String> inferStatic(byte[] word) {
        if (word.length != 32) {
            throw new IllegalArgumentException("word must be 32 bytes");
        }
        var raw = TypeInferrer.inferStatic(word);
        var compact = GeneralizedTypePatterns.generalize(raw);
        return GeneralizedTypePatterns.widenForYamlSearch(raw, compact);
    }

    public static List<String> inferArrayElement(byte[] word) {
        return inferStatic(word);
    }
}
