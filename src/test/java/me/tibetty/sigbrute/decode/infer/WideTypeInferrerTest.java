package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WideTypeInferrerTest {

    @Test
    void wideIncludesBothCompactPatternsAndGreedyCandidates() {
        var word = new byte[32];
        word[31] = 0x01;
        var wide = WideTypeInferrer.inferStatic(word);
        var greedy = TypeInferrer.inferStatic(word);
        var compact = GeneralizedTypeInferrer.inferStatic(word);
        assertTrue(wide.size() >= compact.size());
        assertTrue(wide.containsAll(compact));
        assertTrue(wide.containsAll(greedy));
    }
}
