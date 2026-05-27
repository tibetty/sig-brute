package me.tibetty.sigbrute.decode.infer;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code decode --wide}: union of greedy per-slot candidates and compact heuristic patterns.
 */
public final class WideTypeInferrer {

    private WideTypeInferrer() {
    }

    public static List<String> inferStatic(byte[] word) {
        if (word.length != 32) {
            throw new IllegalArgumentException("word must be 32 bytes");
        }
        var raw = TypeInferrer.inferStatic(word);
        var compact = GeneralizedTypePatterns.widenForYamlSearch(raw,
            GeneralizedTypePatterns.generalize(raw));
        var out = new LinkedHashSet<String>();
        out.addAll(compact);
        out.addAll(raw);
        return List.copyOf(out);
    }

    public static List<String> inferArrayElement(byte[] word) {
        return inferStatic(word);
    }
}
