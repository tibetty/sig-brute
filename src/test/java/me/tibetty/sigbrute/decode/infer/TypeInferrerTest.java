package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import me.tibetty.sigbrute.expander.TypeExpander;
import me.tibetty.sigbrute.util.HexUtil;
import org.junit.jupiter.api.Test;

class TypeInferrerTest {

    private static byte[] w(String hex) {
        var b = HexUtil.fromHex(hex);
        assertEquals(32, b.length);
        return b;
    }

    // ── All-zeros ─────────────────────────────────────────────────────────────

    @Test
    void allZeroSlotIsAmbiguous() {
        var c = TypeInferrer
            .inferStatic(w("0000000000000000000000000000000000000000000000000000000000000000"));
        assertTrue(c.contains("uint*"));
        // int*(0) is valid for any intN — all zero-encoded like uint*(0).
        assertTrue(c.contains("int*"), "int*(0) is zero-encoded; must be a candidate");
        // fixed-point wildcards: zero is the zero value for any fixedMxN / ufixedMxN.
        assertTrue(c.contains("ufixed*"), "ufixed*(0) is zero-encoded; must be a candidate");
        assertTrue(c.contains("fixed*"), "fixed*(0) is zero-encoded; must be a candidate");
        assertTrue(c.contains("address"));
        assertTrue(c.contains("bytes32"));
        // bool(false) is zero-encoded — must appear so functions with bool params are reachable.
        assertTrue(c.contains("bool"), "bool(false) is zero-encoded; must be a candidate");
    }

    // ── Left-aligned (bytesN family) ──────────────────────────────────────────

    @Test
    void leftAlignedFullWordIsBytesOrUint256OrInt256() {
        // byte 0 = 0xb9 (non-zero, non-0xFF), byte 31 = 0x4d (non-zero) → full 32-byte word.
        // bytes32 and uint256 are always valid; int256 is also valid (any 256-bit pattern).
        // No clean sign-extension run (byte 0 ≠ 0xFF) → int256 generic fallback.
        var c = TypeInferrer
            .inferStatic(w("b9af86015b83427d61f2e658cc2c5444e2b4bdde3eadf99c26e5176264f2924d"));
        assertEquals(List.of("bytes32", "uint256", "int256"), c);
    }

    @Test
    void leftAlignedWithTrailingZerosIsExactBytesN() {
        // bytes4: DE AD BE EF followed by 28 zeros
        var c = TypeInferrer
            .inferStatic(w("deadbeef00000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of("bytes4"), c);
    }

    @Test
    void leftAlignedSingleByteIsBytes1() {
        // bytes1: 0xAB followed by 31 zeros
        var c = TypeInferrer
            .inferStatic(w("ab00000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of("bytes1"), c);
    }

    @Test
    void leftAlignedAllFFWordIncludesIntWildcard() {
        // All 32 bytes = 0xFF: bytes32, uint256 (= 2^256−1), and int* (= −1 in any intN).
        // The uint* wildcard must NOT appear (a full-word slot never needs it).
        var c = TypeInferrer
            .inferStatic(w("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        assertEquals(List.of("bytes32", "uint256", "int*"), c);
        assertFalse(c.contains("uint*"), "uint* wildcard should not appear for a full-word slot");
    }

    // ── Address-shaped ────────────────────────────────────────────────────────

    @Test
    void highEntropyAddressShapeIncludesUint160Floor() {
        // 20 non-zero bytes in the body → Keccak-derived address.
        // We keep uint160+ (uint160..uint256) because a uint256 parameter may store an
        // address value (e.g. a token address packed as uint256 in a struct).
        // byte 12 = 0xa0; all 20 body bytes are non-zero → nonZeroCount = 20 ≥ 10.
        var c = TypeInferrer
            .inferStatic(w("000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"));
        assertEquals(List.of("address", "uint160+"), c);

        // uint160+ must expand to uint160 through uint256 (13 types)
        var expanded = TypeExpander.expand("uint160+");
        assertEquals(13, expanded.size());
        assertEquals("uint160", expanded.get(0));
        assertEquals("uint256", expanded.get(expanded.size() - 1));
    }

    @Test
    void lowEntropyAddressShapeRetainsUint160Floor() {
        // firstNonZero == 12, only 1 non-zero byte in the 20-byte body →
        // sparse value that could be a small integer cast to uint160.
        // The value 2^152 requires exactly 153 bits → minimum type is uint160;
        // larger types (uint168 … uint256) are equally valid → uint160+.
        // word: 000...000 | 01 | 000...000 (byte 12 = 0x01, rest zero)
        var c = TypeInferrer
            .inferStatic(w("0000000000000000000000000100000000000000000000000000000000000000"));
        assertEquals(List.of("address", "uint160+"), c);
    }

    // ── Right-aligned uint — minimum-bit-width narrowing ──────────────────────

    @Test
    void smallUintIsNarrowedByMinBits() {
        // hex 000...6a10696f: 28 leading zero bytes → firstNonZero=28, minBits=(32-28)*8=32 →
        // uint32+
        var c = TypeInferrer
            .inferStatic(w("000000000000000000000000000000000000000000000000000000006a10696f"));
        assertEquals(List.of("uint32+"), c);
        // uint32+ must expand to uint32 through uint256
        var expanded = TypeExpander.expand("uint32+");
        assertEquals(29, expanded.size());
        assertEquals("uint32", expanded.get(0));
        assertEquals("uint256", expanded.get(expanded.size() - 1));
    }

    @Test
    void mediumUintIsNarrowedByMinBits() {
        // firstNonZero = 23, minBits = (32-23)*8 = 72 → uint72+
        var c = TypeInferrer
            .inferStatic(w("00000000000000000000000000000000000000000000004828e403f886804492"));
        assertEquals(List.of("uint72+"), c);
        var expanded = TypeExpander.expand("uint72+");
        assertEquals(24, expanded.size());
        assertEquals("uint72", expanded.get(0));
    }

    @Test
    void singleByteValueIsFullUintIntAndFixedWildcard() {
        // value = 1: bool(true), uint8(1), int8(1), and fixedMxN(1/10^N) are all
        // indistinguishable at the binary level — all produce the same 32-byte encoding.
        var c = TypeInferrer
            .inferStatic(w("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals(List.of("uint*", "int*", "ufixed*", "fixed*", "bool"), c);
    }

    @Test
    void boolTrueAndFalseAreCandidates() {
        // bool(false) = all zeros → uint*, int*, ufixed*, fixed*, address, bytes32, bool
        var falseSlot = TypeInferrer
            .inferStatic(w("0000000000000000000000000000000000000000000000000000000000000000"));
        assertTrue(falseSlot.contains("bool"), "bool must be a candidate for the zero slot");
        assertTrue(falseSlot.contains("int*"), "int*(0) must be a candidate for the zero slot");
        assertTrue(falseSlot.contains("ufixed*"), "ufixed*(0) must be a candidate for the zero slot");
        assertTrue(falseSlot.contains("fixed*"), "fixed*(0) must be a candidate for the zero slot");

        // bool(true) = 0x...01 → indistinguishable from uint8(1), int8(1), and fixedMxN(...)
        var trueSlot = TypeInferrer
            .inferStatic(w("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals(List.of("uint*", "int*", "ufixed*", "fixed*", "bool"), trueSlot);

        // value = 2 cannot be bool(true) (invalid bool encoding); bool must NOT appear
        var twoSlot = TypeInferrer
            .inferStatic(w("0000000000000000000000000000000000000000000000000000000000000002"));
        assertFalse(twoSlot.contains("bool"), "bool must not be a candidate for value=2");
    }

    @Test
    void largeRightAlignedIncludesBytes32() {
        // firstNonZero = 1 → minBits = 248 → [uint248+, bytes32]
        var c = TypeInferrer
            .inferStatic(w("00ff000000000000000000000000000000000000000000000000000000000000"));
        // firstNonZero = 1 (byte 1 is 0xFF), so it IS NOT left-aligned (byte 0 is 0x00)
        // → large right-aligned: minBits = (32-1)*8 = 248 → [uint248+, bytes32]
        assertEquals(List.of("uint248+", "bytes32"), c);
        var expanded = TypeExpander.expand("uint248+");
        assertEquals(2, expanded.size()); // uint248, uint256
    }

    // ── uintN+ / uintN- expander coverage ─────────────────────────────────────

    @Test
    void uintFloorExpanderProducesCorrectRange() {
        assertEquals(List.of("uint256"), TypeExpander.expand("uint256+"));
        assertEquals(List.of("uint248", "uint256"), TypeExpander.expand("uint248+"));
        // uint8+ == uint* (no floor constraint)
        assertEquals(TypeExpander.expand("uint*"), TypeExpander.expand("uint8+"));
        // uint64+ should have 25 entries (64, 72, 80, ..., 256)
        assertEquals(25, TypeExpander.expand("uint64+").size());
    }

    @Test
    void uintCeilingExpanderProducesCorrectRange() {
        // uint8- → exactly [uint8] (ceiling at the minimum valid size)
        assertEquals(List.of("uint8"), TypeExpander.expand("uint8-"));
        // uint16- → [uint8, uint16]
        assertEquals(List.of("uint8", "uint16"), TypeExpander.expand("uint16-"));
        // uint64- → 8 entries (8, 16, 24, 32, 40, 48, 56, 64)
        assertEquals(8, TypeExpander.expand("uint64-").size());
        assertEquals("uint8", TypeExpander.expand("uint64-").get(0));
        assertEquals("uint64", TypeExpander.expand("uint64-").get(7));
        // uint256- == uint* (ceiling at the maximum — no narrowing)
        assertEquals(TypeExpander.expand("uint*"), TypeExpander.expand("uint256-"));
    }

    @Test
    void uintFloorExpanderHandlesArraySuffix() {
        // uintN+[] should expand base range and append [] to each type.
        var expanded = TypeExpander.expand("uint160+[]");
        assertEquals(13, expanded.size(), "uint160+[] should expand to 13 array types");
        assertEquals("uint160[]", expanded.get(0));
        assertEquals("uint256[]", expanded.get(expanded.size() - 1));

        // uintN-[] similarly: uint16-[] → [uint8[], uint16[]]
        var ceiling = TypeExpander.expand("uint16-[]");
        assertEquals(List.of("uint8[]", "uint16[]"), ceiling);

        // Fixed-size array suffix is also handled: uint64+[3]
        var fixed = TypeExpander.expand("uint64+[3]");
        assertEquals(25, fixed.size());
        assertEquals("uint64[3]", fixed.get(0));
        assertEquals("uint256[3]", fixed.get(fixed.size() - 1));
    }

    @Test
    void uintFloorAndCeilingAreSymmetric() {
        // uintN+ and uintN- partition uint* at N (with N itself in both).
        var floor = TypeExpander.expand("uint128+"); // 128..256 → 17 types
        var ceil = TypeExpander.expand("uint128-"); // 8..128 → 16 types
        var all = TypeExpander.expand("uint*"); // 8..256 → 32 types

        // floor ∪ ceil covers all types (uint128 appears in both)
        var union = new java.util.LinkedHashSet<>(floor);
        union.addAll(ceil);
        assertEquals(all.size(), union.size());
        assertTrue(union.containsAll(all));

        var overlap = new java.util.ArrayList<>(floor);
        overlap.retainAll(ceil);
        assertEquals(List.of("uint128"), overlap, "floor and ceil intersect only at uint128");
    }

    // ── Packed bytes32 (interior-zero-gap heuristic) ─────────────────────────

    @Test
    void packedBytes32TwoUint128Halves_includesBytes32() {
        // ERC-4337 accountGasLimits: verificationGasLimit packed with callGasLimit.
        // 00000000000000000000000000018b10 | 00000000000000000000000000016160
        // firstNonZero = 13 → would normally be [uint152+] only.
        // Two separated non-zero clusters → bytes32 must be added.
        var c = TypeInferrer
            .inferStatic(w("00000000000000000000000000018b1000000000000000000000000000016160"));
        assertTrue(c.contains("bytes32"), "packed uint128|uint128 slot must include bytes32");
        assertTrue(c.contains("uint152+"), "uint floor estimate must still appear");
        assertEquals("bytes32", c.get(0), "bytes32 should be listed first");
    }

    @Test
    void packedBytes32AddressShaped_includesBytes32() {
        // ERC-4337 gasFees: maxPriorityFeePerGas packed with maxFeePerGas.
        // 00000000000000000000000006eac06d | 0000000000000000000000001743d1d3
        // firstNonZero = 12 → address-shape branch; two separated clusters → bytes32 added.
        var c = TypeInferrer
            .inferStatic(w("00000000000000000000000006eac06d0000000000000000000000001743d1d3"));
        assertTrue(c.contains("bytes32"), "packed uint32|uint32 address-shaped slot must include bytes32");
        assertTrue(c.contains("address"), "address must still appear");
    }

    @Test
    void ordinaryUint_doesNotGainBytes32FromGapHeuristic() {
        // Contiguous non-zero run at the right end — no interior zero gap.
        // uint32: 0x6a10696f at bytes 28-31, all zeros before.
        var c = TypeInferrer
            .inferStatic(w("000000000000000000000000000000000000000000000000000000006a10696f"));
        assertFalse(c.contains("bytes32"), "ordinary uint with contiguous run must not gain bytes32");
        assertEquals(List.of("uint32+"), c);
    }

    @Test
    void ordinaryAddress_doesNotGainBytes32FromGapHeuristic() {
        // Dense high-entropy address — all 20 body bytes non-zero, no interior gap.
        var c = TypeInferrer
            .inferStatic(w("000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"));
        assertFalse(c.contains("bytes32"), "dense address must not gain bytes32 from gap heuristic");
        assertEquals(List.of("address", "uint160+"), c);
    }

    // ── Negative intN — sign-extension detection ──────────────────────────────

    @Test
    void allFFWordAddressesBytesUint256AndIntWildcard() {
        // All 32 bytes = 0xFF: -1 in any intN, also 2^256-1 as uint256, also bytes32.
        var c = TypeInferrer
            .inferStatic(w("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        assertEquals(List.of("bytes32", "uint256", "int*"), c);
    }

    @Test
    void signExtendedFullWordAddsIntFloor() {
        // 28 bytes 0xFF (ffRun=28), then 0xB9 (high bit set) → int32+, then 0x000001.
        // lastNonZero = 31 → full-word case → [bytes32, uint256, int32+].
        // Note: intFloor(8) collapses to int* (int8+ == int*); we use ffRun=28 so minBits=32.
        var c = TypeInferrer
            .inferStatic(w("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffb9000001"));
        assertEquals(List.of("bytes32", "uint256", "int32+"), c);
        // int32+ expands to int32 … int256 (29 types).
        var expanded = TypeExpander.expand("int32+");
        assertEquals(29, expanded.size());
        assertEquals("int32", expanded.get(0));
        assertEquals("int256", expanded.get(expanded.size() - 1));
    }

    @Test
    void signExtendedTrailingZerosAddsIntFloor() {
        // 27 bytes 0xFF, then 0xB9, then 4 zero bytes.
        // firstNonZero=0, lastNonZero=27 → bytesN = bytes28.
        // signExtendedIntCandidate: ffRun=27, byte[27]=0xB9 (high bit set) → int40+.
        var c = TypeInferrer
            .inferStatic(w("ffffffffffffffffffffffffffffffffffffffffffffffffffffffb900000000"));
        assertEquals(List.of("bytes28", "int40+"), c);
    }

    @Test
    void nonSignExtensionTrailingZerosNoBroadening() {
        // DEADBEEF followed by 28 zeros: byte 0 = 0xDE (non-0xFF) → no sign-extension candidate.
        var c = TypeInferrer
            .inferStatic(w("deadbeef00000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of("bytes4"), c);
        assertFalse(c.stream().anyMatch(t -> t.startsWith("int")),
            "non-0xFF byte 0 must not produce any int candidate");
    }

    @Test
    void lowValueTrailingZerosNoIntCandidate() {
        // 31 bytes 0xFF, then 0x00 — first non-0xFF byte has high bit = 0 → not a clean
        // sign-extension. Conservative: emit bytesN only (no int candidate).
        var c = TypeInferrer
            .inferStatic(w("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff00"));
        // firstNonZero=0, lastNonZero=30 → bytes31; intCand=null (0x00 high bit not set).
        assertEquals(List.of("bytes31"), c);
    }

    // ── function type (bytes24-shaped) ────────────────────────────────────────

    @Test
    void bytes24ShapeIncludesFunctionType() {
        // "function" encodes as 20-byte address || 4-byte selector, left-aligned, 8 trailing zeros.
        // Binary representation is bytes24; both must be listed as candidates because they produce
        // different function selectors in sig-brute.
        // bytes 0-19: address (20 bytes); bytes 20-23: selector (4 bytes); bytes 24-31: 0x00 (8).
        var c = TypeInferrer
            .inferStatic(w("1234567890123456789012345678901234567890abcdef010000000000000000"));
        assertEquals(List.of("bytes24", "function"), c);
    }

    @Test
    void bytes8ShapeDoesNotIncludeFunctionType() {
        // bytes8 (lastNonZero=7) is too short for a function encoding — must NOT get "function".
        var c = TypeInferrer
            .inferStatic(w("deadbeefcafebabe000000000000000000000000000000000000000000000000"));
        assertEquals(List.of("bytes8"), c);
        assertFalse(c.contains("function"), "bytes8 shape must not include function candidate");
    }

    // ── fixed* / ufixed* wildcards in zero / value=1 slots ────────────────────

    @Test
    void zeroSlotIncludesFixedPointWildcards() {
        var c = TypeInferrer
            .inferStatic(w("0000000000000000000000000000000000000000000000000000000000000000"));
        assertTrue(c.contains("ufixed*"), "ufixed* must appear in zero slot");
        assertTrue(c.contains("fixed*"), "fixed* must appear in zero slot");
        // Ordinary non-zero uint slot must NOT gain ufixed/fixed wildcards automatically.
        var nonZero = TypeInferrer
            .inferStatic(w("000000000000000000000000000000000000000000000000000000006a10696f"));
        assertFalse(nonZero.contains("ufixed*"), "ufixed* must not appear for non-zero uint slots");
        assertFalse(nonZero.contains("fixed*"), "fixed* must not appear for non-zero uint slots");
    }

    // ── intN+ / intN- expander coverage ───────────────────────────────────────

    @Test
    void intFloorExpanderProducesCorrectRange() {
        assertEquals(List.of("int256"), TypeExpander.expand("int256+"));
        assertEquals(List.of("int248", "int256"), TypeExpander.expand("int248+"));
        // int8+ == int* (no floor constraint at the minimum)
        assertEquals(TypeExpander.expand("int*"), TypeExpander.expand("int8+"));
        // int64+ should have 25 entries (64, 72, 80, ..., 256)
        assertEquals(25, TypeExpander.expand("int64+").size());
        assertEquals("int64", TypeExpander.expand("int64+").get(0));
        assertEquals("int256", TypeExpander.expand("int64+").get(24));
    }

    @Test
    void intCeilingExpanderProducesCorrectRange() {
        // int8- → exactly [int8]
        assertEquals(List.of("int8"), TypeExpander.expand("int8-"));
        // int16- → [int8, int16]
        assertEquals(List.of("int8", "int16"), TypeExpander.expand("int16-"));
        // int64- → 8 entries
        assertEquals(8, TypeExpander.expand("int64-").size());
        assertEquals("int8", TypeExpander.expand("int64-").get(0));
        assertEquals("int64", TypeExpander.expand("int64-").get(7));
        // int256- == int* (ceiling at the max)
        assertEquals(TypeExpander.expand("int*"), TypeExpander.expand("int256-"));
    }

    @Test
    void intFloorExpanderHandlesArraySuffix() {
        // intN+[] should expand base range and append [] to each type.
        var expanded = TypeExpander.expand("int64+[]");
        assertEquals(25, expanded.size());
        assertEquals("int64[]", expanded.get(0));
        assertEquals("int256[]", expanded.get(expanded.size() - 1));

        // intN-[] similarly
        var ceiling = TypeExpander.expand("int16-[]");
        assertEquals(List.of("int8[]", "int16[]"), ceiling);
    }

    @Test
    void intAndUintExpandersAreSymmetric() {
        // int* and uint* must have the same number of types (32 each)
        assertEquals(TypeExpander.expand("uint*").size(), TypeExpander.expand("int*").size());
        // int128+ and uint128+ must have the same count (17 types)
        assertEquals(TypeExpander.expand("uint128+").size(), TypeExpander.expand("int128+").size());
        // int64- and uint64- must have the same count (8 types)
        assertEquals(TypeExpander.expand("uint64-").size(), TypeExpander.expand("int64-").size());
    }

    // ── shapeLabel ────────────────────────────────────────────────────────────

    @Test
    void shapeLabelCoversAllCases() {
        assertEquals("zero", TypeInferrer
            .shapeLabel(w("0000000000000000000000000000000000000000000000000000000000000000")));
        assertEquals("bytes4-shaped", TypeInferrer
            .shapeLabel(w("deadbeef00000000000000000000000000000000000000000000000000000000")));
        assertEquals("full word", TypeInferrer
            .shapeLabel(w("b9af86015b83427d61f2e658cc2c5444e2b4bdde3eadf99c26e5176264f2924d")));
        assertEquals("address", TypeInferrer
            .shapeLabel(w("000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")));
        assertEquals("address-or-uint160", TypeInferrer
            .shapeLabel(w("0000000000000000000000000100000000000000000000000000000000000000")));
        assertEquals("small uint", TypeInferrer
            .shapeLabel(w("000000000000000000000000000000000000000000000000000000006a10696f")));
        assertEquals("medium uint", TypeInferrer
            .shapeLabel(w("00000000000000000000000000000000000000000000004828e403f886804492")));
    }
}
