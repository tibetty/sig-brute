package me.tibetty.sigbrute.decode.infer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;
import org.junit.jupiter.api.Test;

class ValueOneInferrerTest {

    private static final ValueOneInferrer INFERRER = new ValueOneInferrer();

    private static SlotMeta meta(String hex) {
        return SlotMeta.of(HexUtil.fromHex(hex));
    }

    // ── Activation ────────────────────────────────────────────────────────────

    @Test
    void valueOneSlotsReturnsBroadCandidates() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000001"));
        assertEquals(List.of("uint*", "int*", "ufixed*", "fixed*", "bool"), result);
    }

    @Test
    void boolTrueSlotReturnsBroadCandidates() {
        // bool(true) encodes identically to uint8(1) — same slot, same candidates.
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000001"));
        assertTrue(result.contains("bool"));
        assertTrue(result.contains("uint*"));
        assertTrue(result.contains("int*"));
    }

    // ── Non-activation ────────────────────────────────────────────────────────

    @Test
    void allZeroSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of(), result);
    }

    @Test
    void valueTwoSlotReturnsEmpty() {
        // Only value == 1 is special; value == 2 is handled by RightAlignedUintInferrer.
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000002"));
        assertEquals(List.of(), result);
    }

    @Test
    void leftAlignedSlotReturnsEmpty() {
        var result = INFERRER.infer(
            meta("0100000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(List.of(), result);
    }

    @Test
    void addressShapedSlotReturnsEmpty() {
        // firstNonZero == 12; even though last byte could be 1, the value is not exactly 1.
        var result = INFERRER.infer(
            meta("0000000000000000000000006af92da1937360d919a2b9b6760254b18e2aee01"));
        assertEquals(List.of(), result);
    }

    // ── Fixed-point wildcard presence ─────────────────────────────────────────

    @Test
    void ufixedWildcardIsPresent() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000001"));
        assertTrue(result.contains("ufixed*"),
            "ufixed8x1 encodes 1e-N — ufixed* must be a candidate for value=1");
    }

    @Test
    void fixedWildcardIsPresent() {
        var result = INFERRER.infer(
            meta("0000000000000000000000000000000000000000000000000000000000000001"));
        assertTrue(result.contains("fixed*"),
            "fixed8x1 encodes 1e-N — fixed* must be a candidate for value=1");
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    void candidateOrderIsStable() {
        var hex = "0000000000000000000000000000000000000000000000000000000000000001";
        assertEquals(INFERRER.infer(meta(hex)), INFERRER.infer(meta(hex)));
    }
}
