package me.tibetty.sigbrute.decode.strategy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.infer.GeneralizedTypeInferrer;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import org.junit.jupiter.api.Test;

class DecodeStrategyTest {

    @Test
    void greedyAndHeuristicSearchRecoverSameStructureOnDagSwap() throws Exception {
        var fixture = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata");
        var input = CalldataInput.parse(Files.readString(fixture, StandardCharsets.UTF_8));

        var greedy = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes(), DecodeStrategy.GREEDY);
        var search = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes(),
            DecodeStrategy.HEURISTIC_SEARCH);

        assertTrue(SignatureStructure.fromDecodedArgs(greedy)
            .structureEquals(SignatureStructure.fromDecodedArgs(search)));
    }

    @Test
    void heuristicSearchWithoutSkeletonRecoversTransferShape() {
        var hex = "0xa9059cbb"
            + "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
            + "00000000000000000000000000000000000000000000000000000000000003e8";
        var input = CalldataInput.parse(hex);

        var greedy = AbiDecoder.decodeArgs(input.body(), null, DecodeStrategy.GREEDY);
        var search = AbiDecoder.decodeArgs(input.body(), null, DecodeStrategy.HEURISTIC_SEARCH);

        assertEquals(2, greedy.size());
        assertEquals(2, search.size());
        assertTrue(SignatureStructure.fromDecodedArgs(greedy)
            .structureEquals(SignatureStructure.fromDecodedArgs(search)));
    }

    @Test
    void heuristicSearchUsesGeneralizedUintPatterns() {
        var word = new byte[32];
        word[31] = 0x01;
        var greedy = TypeInferrer.inferStatic(word);
        var generalized = GeneralizedTypeInferrer.inferStatic(word);

        assertTrue(greedy.stream().anyMatch(t -> t.startsWith("uint")));
        assertTrue(generalized.contains("uint*") || generalized.contains("uint8+"));
        assertFalse(generalized.stream().anyMatch(t -> t.matches("uint\\d+\\+$") && !t.equals("uint*")));
    }

    @Test
    void heuristicSearchCollapsesAddressSlotToGeneralizedUintFloor() {
        var word = new byte[32];
        word[12] = 0x01;
        word[31] = 0x01;
        var generalized = GeneralizedTypeInferrer.inferStatic(word);

        assertTrue(generalized.contains("address"));
        assertTrue(generalized.contains("uint160+"));
        assertFalse(generalized.contains("uint256"));
    }

    @Test
    void strategyFromIdAcceptsAliases() {
        assertSame(DecodeStrategy.GREEDY, DecodeStrategy.fromId("greedy"));
        assertSame(DecodeStrategy.HEURISTIC_SEARCH, DecodeStrategy.fromId("heuristic"));
        assertSame(DecodeStrategy.HEURISTIC_SEARCH, DecodeStrategy.fromId("search"));
    }

    @Test
    void fromId_treatsNullAndBlankAsGreedy() {
        assertSame(DecodeStrategy.GREEDY, DecodeStrategy.fromId(null));
        assertSame(DecodeStrategy.GREEDY, DecodeStrategy.fromId(""));
        assertSame(DecodeStrategy.GREEDY, DecodeStrategy.fromId("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"banana", "fast", "UNKNOWN", "greedy_extra", "heuristic_search_v2"})
    void fromId_throwsForUnrecognisedId(String id) {
        var ex = assertThrows(IllegalArgumentException.class, () -> DecodeStrategy.fromId(id));
        assertTrue(ex.getMessage().contains("unknown decode strategy"),
            "expected 'unknown decode strategy' in: " + ex.getMessage());
    }

    @Test
    void fromId_canonicalIdRoundTrip() {
        // id() always returns the canonical string; fromId(strategy.id()) must survive the trip.
        for (var strategy : DecodeStrategy.values()) {
            assertSame(strategy, DecodeStrategy.fromId(strategy.id()),
                "round-trip failed for " + strategy.id());
        }
    }
}
