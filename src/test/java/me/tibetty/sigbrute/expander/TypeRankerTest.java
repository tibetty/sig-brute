package me.tibetty.sigbrute.expander;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TypeRankerTest {

    // ── rankOf ────────────────────────────────────────────────────────────────

    @Test
    void uint256RanksFirstAmongUints() {
        assertTrue(TypeRanker.rankOf("uint256") < TypeRanker.rankOf("uint8"),
            "uint256 should be ranked higher (lower ordinal) than uint8");
    }

    @Test
    void addressRanksAboveUint160() {
        assertTrue(TypeRanker.rankOf("address") < TypeRanker.rankOf("uint160"),
            "address should be ranked higher than uint160");
    }

    @Test
    void unknownTypeRanksLast() {
        assertEquals(Integer.MAX_VALUE, TypeRanker.rankOf("nonexistent"),
            "unknown type should return MAX_VALUE");
    }

    // ── rank (list reordering) ────────────────────────────────────────────────

    @Test
    void rankPutsUint256BeforeSmallUints() {
        var input = List.of("uint8", "uint16", "uint32", "uint64", "uint256");
        var ranked = TypeRanker.rank(input);
        assertEquals("uint256", ranked.get(0), "uint256 must be first after ranking");
    }

    @Test
    void rankPreservesAllElements() {
        var input = List.of("uint8", "address", "bool", "bytes32", "uint256");
        var ranked = TypeRanker.rank(input);
        assertEquals(input.size(), ranked.size());
        assertTrue(ranked.containsAll(input));
    }

    @Test
    void rankPrefersBoolOverLargeUints() {
        var input = List.of("uint248", "bool");
        var ranked = TypeRanker.rank(input);
        assertEquals("bool", ranked.get(0), "bool (tier 1) should come before uint248 (tier 7)");
    }

    @Test
    void rankIsStableOnTiesViaNaturalOrder() {
        // Two unknown types → both MAX_VALUE → tie-break by natural String order.
        var input = List.of("zzz_unknown", "aaa_unknown");
        var ranked = TypeRanker.rank(input);
        assertEquals("aaa_unknown", ranked.get(0));
        assertEquals("zzz_unknown", ranked.get(1));
    }

    // ── ceiling pattern (uintN-) + rank ───────────────────────────────────────

    @Test
    void ceilingPatternExpandsAndRanksWithMostCommonFirst() {
        // uint64- expands to [uint8, uint16, uint24, uint32, uint40, uint48, uint56, uint64]
        // After ranking: uint8 (tier 2) and uint64/uint32 (tier 3) come before the rest.
        me.tibetty.sigbrute.model.LeafArgSpec spec = new me.tibetty.sigbrute.model.LeafArgSpec(
            List.of("uint64-"));
        me.tibetty.sigbrute.util.Dimension<String> dim = spec.expand();

        assertEquals(8, dim.size(), "uint64- must expand to 8 types");
        // uint8 is tier-2 (rank 4) — must be tried first
        assertEquals("uint8", dim.get(0));
        // All expanded types must be ≤ uint64
        for (long i = 0; i < dim.size(); i++) {
            var type = dim.get(i);
            assertTrue(type.startsWith("uint"), type + " is not a uint type");
            var bits = Integer.parseInt(type.substring(4));
            assertTrue(bits <= 64, type + " exceeds uint64 ceiling");
        }
    }

    @Test
    void floorAndCeilingPatternsRankIndependently() {
        // uint128+ (17 types, floor) ranked: uint256 first (rank 0)
        me.tibetty.sigbrute.model.LeafArgSpec floor = new me.tibetty.sigbrute.model.LeafArgSpec(
            List.of("uint128+"));
        assertEquals("uint256", floor.expand().get(0));

        // uint128- (16 types, ceiling) ranked: uint8 first (rank 4)
        me.tibetty.sigbrute.model.LeafArgSpec ceil = new me.tibetty.sigbrute.model.LeafArgSpec(
            List.of("uint128-"));
        assertEquals("uint8", ceil.expand().get(0));
    }

    // ── integration: expand + rank via LeafArgSpec ────────────────────────────

    @Test
    void leafArgSpecExpandsUintWildcardWithUint256First() {
        me.tibetty.sigbrute.model.LeafArgSpec spec = new me.tibetty.sigbrute.model.LeafArgSpec(
            List.of("uint*"));
        me.tibetty.sigbrute.util.Dimension<String> dim = spec.expand();

        // Must contain all 32 uint types
        assertEquals(32, dim.size());
        // uint256 must be tried first
        assertEquals("uint256", dim.get(0));
    }

    @Test
    void leafArgSpecExpandsAddressWildcardAndRanksAddress() {
        // Candidates [address, uint160]: address should be first after ranking
        me.tibetty.sigbrute.model.LeafArgSpec spec = new me.tibetty.sigbrute.model.LeafArgSpec(
            List.of("address", "uint160"));
        me.tibetty.sigbrute.util.Dimension<String> dim = spec.expand();
        assertEquals(2, dim.size());
        assertEquals("address", dim.get(0));
        assertEquals("uint160", dim.get(1));
    }
}
