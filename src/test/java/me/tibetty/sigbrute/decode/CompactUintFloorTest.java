package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AbiDecoder#compactUintFloor}.
 *
 * <p>
 * Compaction rule: a maximal consecutive uint run (step = 8) touching the bottom (uint8)
 * or top (uint256) is collapsed to a single YAML-readable pattern.
 *
 * <ul>
 * <li>uint8..uint256 → {@code uint*}
 * <li>uintN..uint256 (N &gt; 8) → {@code uintN+}
 * <li>uint8..uintN (N &lt; 256) → {@code uintN-}
 * <li>bounded both sides, or singleton → left unchanged
 * </ul>
 */
class CompactUintFloorTest {

    // ── no-op cases ───────────────────────────────────────────────────────────

    @Test
    void emptyList_returnedUnchanged() {
        var types = List.of("address", "bytes32");
        assertEquals(types, AbiDecoder.compactUintFloor(types));
    }

    @Test
    void noConcreteUints_returnedUnchanged() {
        var types = List.of("address", "bytes32", "bool");
        assertEquals(types, AbiDecoder.compactUintFloor(types));
    }

    @Test
    void singletonUint_returnedUnchanged() {
        var types = List.of("uint128");
        assertEquals(types, AbiDecoder.compactUintFloor(types));
    }

    @Test
    void boundedBothSidesRun_returnedUnchanged() {
        // uint16..uint128: neither touches bottom (uint8) nor top (uint256)
        var types = List.of("uint16", "uint24", "uint32", "uint40", "uint64", "uint128");
        var result = AbiDecoder.compactUintFloor(types);
        // All six types must still be present (no compaction)
        assertTrue(result.containsAll(types), "bounded run should not be compacted; got: " + result);
        assertEquals(types.size(), result.size());
    }

    @Test
    void nonConsecutiveUints_returnedUnchanged() {
        // gap between uint8 and uint32 breaks the run
        var types = List.of("uint8", "uint32", "uint256");
        var result = AbiDecoder.compactUintFloor(types);
        assertTrue(result.containsAll(types), "non-consecutive uints should not be compacted; got: " + result);
    }

    // ── compaction cases ──────────────────────────────────────────────────────

    @Test
    void fullRange_collapsedToWildcard() {
        // Produce the full uint8..uint256 list programmatically to avoid a 32-entry literal.
        var types = new java.util.ArrayList<String>();
        for (var n = 8; n <= 256; n += 8) {
            types.add("uint" + n);
        }
        var result = AbiDecoder.compactUintFloor(types);
        assertEquals(List.of("uint*"), result, "full uint8..uint256 should compact to uint*");
    }

    @Test
    void floorRun_collapsedToFloorPattern() {
        // uint160..uint256 → uint160+
        var types = new java.util.ArrayList<String>();
        for (var n = 160; n <= 256; n += 8) {
            types.add("uint" + n);
        }
        var result = AbiDecoder.compactUintFloor(types);
        assertEquals(List.of("uint160+"), result, "uint160..uint256 should compact to uint160+");
    }

    @Test
    void ceilingRun_collapsedToCeilingPattern() {
        // uint8..uint128 → uint128-
        var types = new java.util.ArrayList<String>();
        for (var n = 8; n <= 128; n += 8) {
            types.add("uint" + n);
        }
        var result = AbiDecoder.compactUintFloor(types);
        assertEquals(List.of("uint128-"), result, "uint8..uint128 should compact to uint128-");
    }

    @Test
    void twoSeparateQualifyingRuns_bothCompacted() {
        // uint8..uint64 and uint192..uint256 → [uint64-, uint192+]
        var types = new java.util.ArrayList<String>();
        for (var n = 8; n <= 64; n += 8) {
            types.add("uint" + n);
        }
        for (var n = 192; n <= 256; n += 8) {
            types.add("uint" + n);
        }
        var result = AbiDecoder.compactUintFloor(types);
        assertTrue(result.contains("uint64-"), "lower run should become uint64-; got: " + result);
        assertTrue(result.contains("uint192+"), "upper run should become uint192+; got: " + result);
        assertEquals(2, result.size(), "exactly two patterns expected; got: " + result);
    }

    @Test
    void nonUintTypesPreservedAlongsideCompactedRun() {
        // [address, uint8, uint16, ..., uint256] → [address, uint*]
        var types = new java.util.ArrayList<String>();
        types.add("address");
        for (var n = 8; n <= 256; n += 8) {
            types.add("uint" + n);
        }
        var result = AbiDecoder.compactUintFloor(types);
        assertTrue(result.contains("address"), "address must be preserved; got: " + result);
        assertTrue(result.contains("uint*"), "uint range should compact to uint*; got: " + result);
        assertEquals(2, result.size(), "only address + uint* expected; got: " + result);
    }

    @Test
    void wildcardAndPatternInputsArePassedThroughUnchanged() {
        // compactUintFloor only processes concrete uintN types (isConcreteUint check);
        // pattern types like uint* or uint160+ must pass through unchanged.
        var types = List.of("uint*", "uint160+");
        var result = AbiDecoder.compactUintFloor(types);
        assertEquals(types, result, "non-concrete uint patterns should not be modified");
    }
}
