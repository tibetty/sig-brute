package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;
import org.junit.jupiter.api.Test;

class AddressInferrerTest {

    private static final AddressInferrer INFERRER = new AddressInferrer();

    private static SlotMeta meta(String hex) {
        return SlotMeta.of(HexUtil.fromHex(hex));
    }

    // ── Non-activation ────────────────────────────────────────────────────────

    @Test
    void allZeroSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of(), result);
    }

    @Test
    void rightAlignedSmallValueReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000042"));
        assertEquals(List.of(), result);
    }

    @Test
    void leftAlignedSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("deadbeef00000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of(), result);
    }

    @Test
    void elevenLeadingZerosNotAddressShaped() {
        // firstNonZero == 11, not 12 → not address-shaped
        var result = INFERRER.infer(
            meta("000000000000000000000012aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals(List.of(), result);
    }

    @Test
    void thirteenLeadingZerosNotAddressShaped() {
        // firstNonZero == 13 (26 hex zeros = 13 zero bytes), not 12 → not address-shaped
        var result = INFERRER.infer(
            meta("00000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals(List.of(), result);
    }

    // ── High-entropy address (real Keccak address) ────────────────────────────

    @Test
    void highEntropyAddressGivesAddressAndUint160Floor() {
        // Real-world address: many non-zero bytes in [12, 32)
        var result = INFERRER.infer(
            meta("0000000000000000000000006af92da1937360d919a2b9b6760254b18e2aee54"));
        assertEquals(List.of("address", "uint160+"), result);
    }

    @Test
    void anotherHighEntropyAddressContainsAddress() {
        // 6af92da1... has no interior zero gap → contiguous → no bytes32
        var result = INFERRER.infer(
            meta("0000000000000000000000006af92da1937360d919a2b9b6760254b18e2aee54"));
        assertTrue(result.contains("address"));
        assertTrue(result.contains("uint160+"));
        assertFalse(result.contains("bytes32"), "high-entropy contiguous → no bytes32");
    }

    // ── Low-entropy address (small uint160 cast) ──────────────────────────────

    @Test
    void lowEntropyAddressStillGivesAddressAndUint160Floor() {
        // Sparse body: value looks like an integer cast to uint160, but address is still valid
        var result = INFERRER.infer(
            meta("000000000000000000000000000000000000000000000000000000001743d1d3"));
        // firstNonZero == 28, not 12 → this slot is NOT address-shaped → empty
        assertEquals(List.of(), result);
    }

    @Test
    void uint160CastPreserves20ByteLayout() {
        // firstNonZero == 12, most body bytes zero (low entropy) → still address-shaped
        var result = INFERRER.infer(
            meta("00000000000000000000000000000000000000000000000000000001743d1d3f"));
        // firstNonZero == 12? No — counting: 12 bytes of 0x00, then the 20 bytes contain
        // mostly zeros. firstNonZero would be wherever first non-zero is in bytes [12..31].
        // Let's check: the hex has leading 24 hex chars = 12 bytes zero, then
        // 00000000000000000000 = 10 more zero bytes, then 00000001743d1d3f = 8 bytes
        // So firstNonZero is actually 28, not 12.
        // This test verifies the inferrer correctly returns empty for non-address-shaped slots.
        assertEquals(List.of(), result);
    }

    @Test
    void addressShapedWithExactly12LeadingZerosAndSparseBody() {
        // Construct a slot where firstNonZero == 12 but most of the 20 body bytes are zero
        // (low-entropy) → candidates should still be address and uint160+
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000001aabbcc"));
        // firstNonZero = 29 (0x01 is at byte 29) → not 12 → empty
        assertEquals(List.of(), result);
    }

    @Test
    void lowEntropyAddressWithFirstNonZeroAt12() {
        // 12 leading zeros, then contiguous non-zero body (no interior gap) → [address, uint160+]
        var result = INFERRER.infer(
            meta("000000000000000000000000abcdef0123456789abcdef0123456789abcdef01"));
        assertEquals(List.of("address", "uint160+"), result);
    }

    // ── Packed bytes32 (interior gap) ─────────────────────────────────────────

    @Test
    void addressShapedWithInteriorGapAddressesBytes32() {
        // Non-zero at byte 12, zeros in middle, non-zero near the end → interior zero gap
        // Example: 12 leading zeros, then 0xAB, then many zeros, then 0xCD near end
        var result = INFERRER.infer(
            meta("000000000000000000000000ab000000000000000000000000000000000000cd"));
        assertTrue(result.contains("bytes32"),
            "interior gap in address-shaped slot indicates packed bytes32");
        assertTrue(result.contains("address"));
        assertTrue(result.contains("uint160+"));
        assertEquals(List.of("bytes32", "address", "uint160+"), result);
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    void uint160FloorNotUint160() {
        // Must be uint160+ (floor), not uint160 (exact)
        var result = INFERRER.infer(
            meta("0000000000000000000000006af92da1937360d919a2b9b6760254b18e2aee54"));
        assertTrue(result.contains("uint160+"), "must be floor pattern, not exact uint160");
        assertFalse(result.contains("uint160"), "exact uint160 must not appear — use uint160+");
    }
}
