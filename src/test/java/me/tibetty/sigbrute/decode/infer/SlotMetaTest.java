package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import me.tibetty.sigbrute.util.HexUtil;
import org.junit.jupiter.api.Test;

class SlotMetaTest {

    private static SlotMeta meta(String hex) {
        return SlotMeta.of(HexUtil.fromHex(hex));
    }

    /** Counts non-zero bytes in the address-entropy body: word[12..32). */
    private static int nonZeroInBody(SlotMeta m) {
        var count = 0;
        for (var i = 12; i < 32; i++) {
            if ((m.word()[i] & 0xFF) != 0) {
                count++;
            }
        }
        return count;
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    void tooShortWordThrows() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> SlotMeta.of(new byte[31]));
        assertEquals("word must be 32 bytes", ex.getMessage());
    }

    @Test
    void tooLongWordThrows() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> SlotMeta.of(new byte[33]));
        assertEquals("word must be 32 bytes", ex.getMessage());
    }

    @Test
    void emptyArrayThrows() {
        assertThrows(IllegalArgumentException.class, () -> SlotMeta.of(new byte[0]));
    }

    // ── All-zero word ─────────────────────────────────────────────────────────

    @Test
    void allZeroFirstNonZeroSentinel() {
        assertEquals(32,
            meta("0000000000000000000000000000000000000000000000000000000000000000")
                .firstNonZero());
    }

    @Test
    void allZeroLastNonZeroSentinel() {
        assertEquals(-1,
            meta("0000000000000000000000000000000000000000000000000000000000000000")
                .lastNonZero());
    }

    @Test
    void allZeroFfRunIsZero() {
        assertEquals(0,
            meta("0000000000000000000000000000000000000000000000000000000000000000")
                .ffRun());
    }

    @Test
    void allZeroHasNoGapAndLowEntropy() {
        var m = meta("0000000000000000000000000000000000000000000000000000000000000000");
        assertFalse(m.hasGap());
        assertFalse(m.highEntropy());
    }

    @Test
    void allZeroPredicatesConsistent() {
        var m = meta("0000000000000000000000000000000000000000000000000000000000000000");
        assertTrue(m.isAllZero());
        assertFalse(m.isLeftAligned());
        assertFalse(m.isFullWord());
        assertFalse(m.isAddressShaped());
        assertFalse(m.isValueOne());
        assertEquals(0, m.minBits()); // (32 − 32) × 8
    }

    // ── All-0xFF word ─────────────────────────────────────────────────────────

    @Test
    void allFFComputedFields() {
        var m = meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        assertEquals(0, m.firstNonZero());
        assertEquals(31, m.lastNonZero());
        assertEquals(32, m.ffRun());
        assertFalse(m.hasGap());     // all bytes non-zero — no interior zero gap
        assertTrue(m.highEntropy()); // 20 / 20 body bytes non-zero
    }

    @Test
    void allFFPredicatesConsistent() {
        var m = meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        assertFalse(m.isAllZero());
        assertTrue(m.isLeftAligned());
        assertTrue(m.isFullWord());
        assertFalse(m.isAddressShaped()); // firstNonZero == 0, not 12
        assertFalse(m.isValueOne());
        assertEquals(256, m.minBits()); // (32 − 0) × 8
    }

    // ── ffRun field ───────────────────────────────────────────────────────────

    @Test
    void ffRunZeroWhenFirstByteNotFF() {
        // byte 0 = 0xAB (not 0xFF) → ffRun = 0
        assertEquals(0,
            meta("ab00000000000000000000000000000000000000000000000000000000000000")
                .ffRun());
    }

    @Test
    void ffRunOneWhenExactlyOneLeadingFF() {
        // byte 0 = 0xFF, byte 1 = 0x80 (not 0xFF) → ffRun = 1
        assertEquals(1,
            meta("ff80000000000000000000000000000000000000000000000000000000000000")
                .ffRun());
    }

    @Test
    void ffRunTwoWhenExactlyTwoLeadingFF() {
        // bytes 0–1 = 0xFF, byte 2 = 0x80 (not 0xFF) → ffRun = 2
        assertEquals(2,
            meta("ffff800000000000000000000000000000000000000000000000000000000000")
                .ffRun());
    }

    @Test
    void ffRunBreaksOnFirstNonFF() {
        // byte 0 = 0xFF, byte 1 = 0x00 (not 0xFF), byte 2 = 0xFF (not counted) → ffRun = 1
        assertEquals(1,
            meta("ff00ff000000000000000000000000000000000000000000000000000000dead")
                .ffRun());
    }

    // ── hasGap field ─────────────────────────────────────────────────────────

    @Test
    void contiguousRunHasNoGap() {
        // bytes 28–31 non-zero, single contiguous cluster
        assertFalse(
            meta("00000000000000000000000000000000000000000000000000000000deadbeef")
                .hasGap());
    }

    @Test
    void twoSeparatedClustersHaveGap() {
        // byte 0 = 0xAB, bytes 1–30 zero, byte 31 = 0xCD → two separated clusters
        assertTrue(
            meta("ab000000000000000000000000000000000000000000000000000000000000cd")
                .hasGap());
    }

    @Test
    void singleNonZeroByteHasNoGap() {
        // Only byte 0 is non-zero — one cluster, nothing after to form a second
        assertFalse(
            meta("ab00000000000000000000000000000000000000000000000000000000000000")
                .hasGap());
    }

    @Test
    void innerGapDetected() {
        // bytes 0–3 non-zero, 24 interior zero bytes, bytes 28–31 non-zero → gap
        assertTrue(
            meta("deadbeef000000000000000000000000000000000000000000000000cafebabe")
                .hasGap());
    }

    @Test
    void allZeroWordHasNoGap() {
        // gap scan is skipped entirely when firstNonZero == 32
        assertFalse(
            meta("0000000000000000000000000000000000000000000000000000000000000000")
                .hasGap());
    }

    // ── highEntropy boundary ──────────────────────────────────────────────────

    @Test
    void exactly10Of20BodyBytesNonZeroMeetsThreshold() {
        // bytes[12..22) = 0xAA (10 non-zero), bytes[22..32) = 0x00 (10 zero)
        var m = meta("000000000000000000000000aaaaaaaaaaaaaaaaaaaa00000000000000000000");
        assertEquals(10, nonZeroInBody(m));
        assertTrue(m.highEntropy());
    }

    @Test
    void exactly9Of20BodyBytesNonZeroIsBelowThreshold() {
        // bytes[12..21) = 0xAA (9 non-zero), bytes[21..32) = 0x00 (11 zero)
        var m = meta("000000000000000000000000aaaaaaaaaaaaaaaaaa0000000000000000000000");
        assertEquals(9, nonZeroInBody(m));
        assertFalse(m.highEntropy());
    }

    @Test
    void all20BodyBytesNonZeroIsHighEntropy() {
        // bytes[12..32) all = 0xAA → 20 / 20 non-zero
        var m = meta("000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals(20, nonZeroInBody(m));
        assertTrue(m.highEntropy());
    }

    @Test
    void nonZeroPreBodyBytesDoNotCountTowardEntropy() {
        // bytes[0..12) = 0xAB (outside body), bytes[12..32) = 0x00 → body count = 0
        var m = meta("abababababababababababab0000000000000000000000000000000000000000");
        assertEquals(0, nonZeroInBody(m));
        assertFalse(m.highEntropy());
    }

    // ── firstNonZero / lastNonZero ────────────────────────────────────────────

    @Test
    void rightAlignedSingleByteFirstAndLastNonZero() {
        // Only byte 31 non-zero → firstNonZero = 31, lastNonZero = 31
        var m = meta("0000000000000000000000000000000000000000000000000000000000000042");
        assertEquals(31, m.firstNonZero());
        assertEquals(31, m.lastNonZero());
    }

    @Test
    void leftAlignedTrailingZerosLastNonZero() {
        // DEADBEEF in bytes 0–3, trailing zeros → lastNonZero = 3
        var m = meta("deadbeef00000000000000000000000000000000000000000000000000000000");
        assertEquals(0, m.firstNonZero());
        assertEquals(3, m.lastNonZero());
    }

    @Test
    void addressShapedFirstNonZeroAt12() {
        // 12 leading zero bytes, then non-zero → firstNonZero = 12
        var m = meta("000000000000000000000000abcdef0123456789abcdef0123456789abcdef01");
        assertEquals(12, m.firstNonZero());
        assertTrue(m.isAddressShaped());
    }

    // ── isValueOne ────────────────────────────────────────────────────────────

    @Test
    void isValueOneTrueWhenByte31Equals1AndAllOthersZero() {
        assertTrue(
            meta("0000000000000000000000000000000000000000000000000000000000000001")
                .isValueOne());
    }

    @Test
    void isValueOneFalseWhenByte31EqualsTwo() {
        assertFalse(
            meta("0000000000000000000000000000000000000000000000000000000000000002")
                .isValueOne());
    }

    @Test
    void isValueOneFalseWhenFirstNonZeroIsNotByte31() {
        // byte 30 = 0x01, byte 31 = 0x01 → firstNonZero = 30, not 31 → isValueOne = false
        assertFalse(
            meta("0000000000000000000000000000000000000000000000000000000000000101")
                .isValueOne());
    }

    // ── isAddressShaped ───────────────────────────────────────────────────────

    @Test
    void isAddressShapedTrueAtExactly12LeadingZeros() {
        assertTrue(
            meta("000000000000000000000000abcdef0123456789abcdef0123456789abcdef01")
                .isAddressShaped());
    }

    @Test
    void isAddressShapedFalseAt11LeadingZeros() {
        // firstNonZero = 11, not 12
        assertFalse(
            meta("000000000000000000000012aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .isAddressShaped());
    }

    @Test
    void isAddressShapedFalseAt13LeadingZeros() {
        // firstNonZero = 13, not 12
        assertFalse(
            meta("00000000000000000000000000abcdef0123456789abcdef0123456789abcdef")
                .isAddressShaped());
    }

    // ── minBits ───────────────────────────────────────────────────────────────

    @Test
    void minBitsIsZeroForAllZeroWord() {
        // firstNonZero = 32 → (32 − 32) × 8 = 0
        assertEquals(0,
            meta("0000000000000000000000000000000000000000000000000000000000000000")
                .minBits());
    }

    @Test
    void minBitsIs256ForFullWord() {
        // firstNonZero = 0 → (32 − 0) × 8 = 256
        assertEquals(256,
            meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
                .minBits());
    }

    @Test
    void minBitsIs160ForAddressShaped() {
        // firstNonZero = 12 → (32 − 12) × 8 = 160
        assertEquals(160,
            meta("000000000000000000000000abcdef0123456789abcdef0123456789abcdef01")
                .minBits());
    }

    @Test
    void minBitsIs32ForSmallValue() {
        // firstNonZero = 28 → (32 − 28) × 8 = 32
        assertEquals(32,
            meta("000000000000000000000000000000000000000000000000000000001234abcd")
                .minBits());
    }

    @Test
    void minBitsIs8ForSingleLastByte() {
        // firstNonZero = 31 → (32 − 31) × 8 = 8
        assertEquals(8,
            meta("0000000000000000000000000000000000000000000000000000000000000001")
                .minBits());
    }
}
