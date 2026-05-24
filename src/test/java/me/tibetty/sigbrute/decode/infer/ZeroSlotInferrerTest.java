package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;
import org.junit.jupiter.api.Test;

class ZeroSlotInferrerTest {

    private static final ZeroSlotInferrer INFERRER = new ZeroSlotInferrer();

    private static SlotMeta meta(String hex) {
        return SlotMeta.of(HexUtil.fromHex(hex));
    }

    // ── Activation ────────────────────────────────────────────────────────────

    @Test
    void allZeroSlotReturnsBroadCandidates() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of("uint*", "int*", "ufixed*", "fixed*", "address", "bytes32", "bool"),
            result);
    }

    // ── Non-activation ────────────────────────────────────────────────────────

    @Test
    void nonZeroSlotReturnsEmpty() {
        // Value = 1: a right-aligned non-zero slot must not trigger this inferrer.
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

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    void candidateOrderIsStable() {
        var r1 = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000000"));
        var r2 = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(r1, r2);
    }
}
