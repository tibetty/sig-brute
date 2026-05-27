package me.tibetty.sigbrute.decode.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.tibetty.sigbrute.decode.DecodedArg;

import java.util.concurrent.Executors;
import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.SignatureStructure;
import org.junit.jupiter.api.Test;

class DecodeContextTest {

    @Test
    void greedyAndHeuristicContextsAreIndependentInstances() {
        assertNotSame(DecodeContext.greedy(), DecodeContext.heuristicSearch());
        assertNotSame(DecodeContext.heuristicSearch(), DecodeContext.heuristicSearchWide());
    }

    @Test
    void recordAlternate_dedupesByStructure() {
        var ctx = DecodeContext.heuristicSearch();
        var shapeA = List.<DecodedArg>of(new DecodedArg.Leaf(List.of("uint256"), null));
        var shapeB = List.<DecodedArg>of(new DecodedArg.PrimArray("[]", List.of("address"), null));
        ctx.recordAlternate(shapeB, "head:64");
        ctx.recordAlternate(shapeB, "head:96");
        ctx.recordAlternate(shapeA, "flat");
        assertEquals(2, ctx.alternateStructures().size());
    }

    @Test
    void parallelDecodesWithDifferentStrategiesDoNotCrossContaminate() throws Exception {
        var hex = "0xa9059cbb"
            + "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
            + "00000000000000000000000000000000000000000000000000000000000003e8";
        var input = CalldataInput.parse(hex);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var greedy = pool.submit(() -> AbiDecoder.decodeArgs(input.body(), null,
                DecodeStrategy.GREEDY));
            var search = pool.submit(() -> AbiDecoder.decodeArgs(input.body(), null,
                DecodeStrategy.HEURISTIC_SEARCH));
            var greedyArgs = greedy.get();
            var searchArgs = search.get();
            assertEquals(2, greedyArgs.size());
            assertTrue(SignatureStructure.fromDecodedArgs(greedyArgs)
                .structureEquals(SignatureStructure.fromDecodedArgs(searchArgs)));
        } finally {
            pool.shutdownNow();
        }
    }
}
