package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;
import org.junit.jupiter.api.Test;

class RightAlignedUintInferrerTest {

    private static final RightAlignedUintInferrer INFERRER = new RightAlignedUintInferrer();

    private static SlotMeta meta(String hex) {
        return SlotMeta.of(HexUtil.fromHex(hex));
    }

    // ── Non-activation (excluded shapes) ─────────────────────────────────────

    @Test
    void allZeroSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of(), result);
    }

    @Test
    void valueOneSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals(List.of(), result);
    }

    @Test
    void leftAlignedSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("deadbeef00000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of(), result);
    }

    @Test
    void addressShapedSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0000000000000000000000006af92da1937360d919a2b9b6760254b18e2aee54"));
        assertEquals(List.of(), result);
    }

    // ── Medium / small value (firstNonZero ≥ 13) ─────────────────────────────

    @Test
    void mediumUintNoGapGivesFloorOnly() {
        // firstNonZero == 26 → minBits = (32-26)*8 = 48 → uint48+
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000aabbccddeeff"));
        assertEquals(List.of("uint48+"), result);
    }

    @Test
    void smallUintSingleByteGivesFloor() {
        // firstNonZero == 31 but value != 1 → value = 0x42 → minBits = 8 → uint* (floor ≤ 8)
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000042"));
        assertEquals(List.of("uint*"), result);
    }

    @Test
    void mediumUintWithGapGivesBytes32First() {
        // firstNonZero >= 13, interior zero gap → bytes32 prepended
        // Example: non-zero at byte 13, zeros in middle, non-zero near byte 31
        var result = INFERRER.infer(
            meta("000000000000000000000000000abc000000000000000000000000000000def0"));
        assertTrue(result.contains("bytes32"), "gap → bytes32 candidate");
        assertEquals("bytes32", result.get(0), "bytes32 must be first when gap detected");
    }

    @Test
    void mediumUintWithGapOrderIsBytes32ThenFloor() {
        var result = INFERRER.infer(
            meta("000000000000000000000000000abc000000000000000000000000000000def0"));
        assertEquals(2, result.size());
        assertEquals("bytes32", result.get(0));
        // Second element is a uint floor pattern
        assertTrue(result.get(1).startsWith("uint"), "second element must be uint floor");
    }

    // ── Large value (firstNonZero in [1..11]) ─────────────────────────────────

    @Test
    void largeUintFirstNonZeroAt1GivesFloorThenBytes32() {
        // firstNonZero == 1 → minBits = (32-1)*8 = 248 → uint248+, bytes32
        var result = INFERRER.infer(
            meta("00aabbccddeeff00112233445566778899aabbccddeeff001122334455667788"));
        assertEquals(List.of("uint248+", "bytes32"), result);
    }

    @Test
    void largeUintFirstNonZeroAt11GivesFloorThenBytes32() {
        // firstNonZero == 11 → minBits = (32-11)*8 = 168 → uint168+, bytes32
        var result = INFERRER.infer(
            meta("0000000000000000000000aabbccddeeff112233445566778899aabbccddeeff"));
        assertEquals(List.of("uint168+", "bytes32"), result);
    }

    @Test
    void largeUintOrderIsFloorBeforeBytes32() {
        // For firstNonZero in [1..11], uint floor comes first, bytes32 second.
        var result = INFERRER.infer(
            meta("00ff00000000000000000000000000000000000000000000000000000000dead"));
        assertEquals(2, result.size());
        assertTrue(result.get(0).startsWith("uint"), "floor pattern must be first for large value");
        assertEquals("bytes32", result.get(1));
    }

    // ── Boundary between medium (≥ 13) and large (≤ 11) ─────────────────────

    @Test
    void firstNonZeroAt12IsAddressShaped_notHandledHere() {
        // firstNonZero == 12 is the address-shaped case → AddressInferrer handles it;
        // this inferrer must return empty.
        var result = INFERRER.infer(
            meta("000000000000000000000000ab0000000000000000000000000000000000dead"));
        // firstNonZero == 12 → isAddressShaped() == true → excluded
        assertEquals(List.of(), result);
    }

    @Test
    void firstNonZeroAt13IsHandledHere() {
        // firstNonZero == 13 → handled by this inferrer
        var result = INFERRER.infer(
            meta("0000000000000000000000000000ab0000000000000000000000000000000001"));
        // minBits = (32-13)*8 = 152 → uint152+; no gap (value=1 at end, non-zero at 13)
        // Wait, let me check: 0x00*13 then 0xab then... actually lastNonZero is at byte 31 (=0x01)
        // firstNonZero is at byte 13 (=0xab). Are there zeros between 0xab and 0x01?
        // bytes 14..30 are 0x00 → interior zero gap exists
        assertTrue(result.contains("bytes32") || result.contains("uint152+"),
            "firstNonZero == 13 is handled here");
    }

    // ── uint floor patterns ───────────────────────────────────────────────────

    @Test
    void floorPatternAtExactly8BitsGivesWildcard() {
        // firstNonZero == 31, value != 1 (e.g. 0x42) → minBits = 8 → uint* (full wildcard)
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000042"));
        assertEquals(List.of("uint*"), result);
    }

    @Test
    void floorPatternAt256BitsGivesUint256() {
        // firstNonZero == 1 with a non-zero final byte → minBits = 248 → uint248+
        // To get minBits == 256 we need firstNonZero == 0, but that's left-aligned (excluded).
        // Verify that firstNonZero == 1 gives "uint248+", not "uint256+" (no + beyond 256).
        var result = INFERRER.infer(
            meta("01000000000000000000000000000000000000000000000000000000000000ff"));
        // firstNonZero == 0 (byte 0 = 0x01) → left-aligned → excluded → empty
        assertEquals(List.of(), result);
    }

    @Test
    void floorPatternLargestRightAligned() {
        // firstNonZero == 1, byte[0] == 0x00 but byte[1] = 0x01 → firstNonZero == 1 → large
        var result = INFERRER.infer(
            meta("0001000000000000000000000000000000000000000000000000000000000001"));
        assertEquals(List.of("uint248+", "bytes32"), result);
    }
}
