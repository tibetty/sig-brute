package me.tibetty.sigbrute.decode.strategy.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.infer.GeneralizedTypePatterns;
import me.tibetty.sigbrute.decode.strategy.DecodeContext;
import me.tibetty.sigbrute.decode.SignatureStructure;
import org.junit.jupiter.api.Test;

class SearchBodyDecoderTest {

    @Test
    void heuristicDecodeIsDeterministicAcrossRepeatedRuns() {
        var hex = "0xa9059cbb"
            + "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
            + "00000000000000000000000000000000000000000000000000000000000003e8";
        var body = me.tibetty.sigbrute.decode.CalldataInput.parse(hex).body();
        var first = SearchBodyDecoder.decode(DecodeContext.heuristicSearch(), body);
        var expected = SignatureStructure.fromDecodedArgs(first);
        for (var i = 0; i < 20; i++) {
            var again = SearchBodyDecoder.decode(DecodeContext.heuristicSearch(), body);
            assertEquals(first.size(), again.size());
            assertTrue(expected.structureEquals(SignatureStructure.fromDecodedArgs(again)));
        }
    }

    @Test
    void nearTieSameStructureMergesLeafCandidates() {
        var ctx = DecodeContext.heuristicSearch();
        var word = new byte[32];
        word[31] = 0x01;
        var a = new DecodedArg.Leaf(List.of("uint8+"), "a");
        var b = new DecodedArg.Leaf(List.of("uint256"), "b");
        var merged = (DecodedArg.Leaf) SearchBodyDecoder.mergeArgsForTests(a, b);
        assertTrue(merged.candidates().contains("uint8+"));
        assertTrue(merged.candidates().contains("uint256"));
        assertEquals(0, ctx.warnings().size());
    }

    @Test
    void widenForYamlSearchAddsStringWhenBytesFamilyCollapsed() {
        var raw = List.of("bytes", "string", "bytes32");
        var compact = GeneralizedTypePatterns.generalize(raw);
        var wide = GeneralizedTypePatterns.widenForYamlSearch(raw, compact);
        assertTrue(wide.contains("string"));
        assertTrue(wide.contains("bytes"));
    }
}
