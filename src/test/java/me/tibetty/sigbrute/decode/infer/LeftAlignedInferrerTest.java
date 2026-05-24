package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;
import org.junit.jupiter.api.Test;

class LeftAlignedInferrerTest {

    private static final LeftAlignedInferrer INFERRER = new LeftAlignedInferrer();

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
    void rightAlignedSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000042"));
        assertEquals(List.of(), result);
    }

    // ── Full word (lastNonZero == 31) ─────────────────────────────────────────

    @Test
    void fullWordNoSignExtGivesInt256() {
        // byte 0 = 0xb9 (not 0xFF) → no sign-extension pattern → fallback to int256
        var result = INFERRER.infer(
            meta("b9af86015b83427d61f2e658cc2c5444e2b4bdde3eadf99c26e5176264f2924d"));
        assertEquals(List.of("bytes32", "uint256", "int256"), result);
    }

    @Test
    void allFFWordGivesIntWildcard() {
        // All 0xFF → ffRun == 32 → signExtendedIntCandidate returns "int*"
        var result = INFERRER.infer(
            meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        assertEquals(List.of("bytes32", "uint256", "int*"), result);
    }

    @Test
    void fullWordWithCleanSignExtGivesIntFloor() {
        // ffRun == 28 bytes of 0xFF, then 0xb9 (high bit set) → int32+
        var result = INFERRER.infer(
            meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffb9000001"));
        assertEquals(List.of("bytes32", "uint256", "int32+"), result);
    }

    @Test
    void fullWordSignExtHighBitClearGivesInt256Fallback() {
        // 0xFF run, then byte with high bit clear (0x7F) → not a clean sign-extension → int256
        var result = INFERRER.infer(
            meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffff7f00000001"));
        assertEquals(List.of("bytes32", "uint256", "int256"), result);
    }

    // ── Trailing zeros (lastNonZero < 31) ────────────────────────────────────

    @Test
    void trailingZerosGivesTightBytesN() {
        // bytes4: DEADBEEF followed by 28 zeros
        var result = INFERRER.infer(
            meta("deadbeef00000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of("bytes4"), result);
    }

    @Test
    void trailingZerosWithSignExtGivesIntFloor() {
        // ffRun == 28, then 0xa0 (high bit set), then 3 trailing zeros → int32+
        var result = INFERRER.infer(
            meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffa0000000"));
        assertEquals(List.of("bytes29", "int32+"), result);
    }

    @Test
    void trailingZerosSignExtHighBitClearNoCand() {
        // 29 bytes of 0xFF, then 0x40 (high bit clear) → not sign-extension → bytesN only
        // lastNonZero = 29 → bytes30; ffRun = 29 but word[29] = 0x40 has high bit clear
        var result = INFERRER.infer(
            meta("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff400000"));
        assertEquals(List.of("bytes30"), result);
    }

    // ── bytes24 / function shape ──────────────────────────────────────────────

    @Test
    void bytes24ShapeIncludesFunction() {
        // 20-byte address + 4-byte selector left-aligned, 8 trailing zeros
        var result = INFERRER.infer(
            meta("1234567890123456789012345678901234567890abcdef010000000000000000"));
        assertEquals(List.of("bytes24", "function"), result);
    }

    @Test
    void bytes24ShapeWithSignExtIncludesIntFloor() {
        // bytes24 shape but byte 0 is 0xFF and there is a clean sign-extension run into bytes[23]
        // ffRun covers enough bytes; first non-0xFF byte at position determined by pattern.
        // Construct: 23 bytes of 0xFF, then 0xa0 (high bit set), then 8 zeros → lastNonZero == 23
        var result = INFERRER.infer(
            meta("ffffffffffffffffffffffffffffffffffffffffffffffa0000000000000000000000000"
                .substring(0, 64)));
        // bytes24 (lastNonZero+1=24), function, int72+ (ffRun=23 → minBits=(32-23)*8=72)
        assertEquals(List.of("bytes24", "function", "int72+"), result);
    }

    // ── Sign-extension edge cases ─────────────────────────────────────────────

    @Test
    void singleFFByteLeadingHighBitNextByteGivesInt248Floor() {
        // ffRun == 1, word[1] has high bit set (0x80) → minBits = (32-1)*8 = 248 → int248+
        var result = INFERRER.infer(
            meta("ff80000000000000000000000000000000000000000000000000000000000000"));
        // lastNonZero == 1 → bytes2 shape; int248+ candidate
        assertEquals(List.of("bytes2", "int248+"), result);
    }

    @Test
    void minBitsAt256GivesInt256NotFloor() {
        // ffRun == 0 means no sign-extension (byte 0 = 0xa0, not 0xFF) → int256 fallback
        // This is a full-word test: byte 0 = 0xa0, byte 31 = 0x01
        var result = INFERRER.infer(
            meta("a000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals(List.of("bytes32", "uint256", "int256"), result);
    }
}
