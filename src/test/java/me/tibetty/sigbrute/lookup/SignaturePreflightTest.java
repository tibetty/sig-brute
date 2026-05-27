package me.tibetty.sigbrute.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.tibetty.sigbrute.util.Keccak256Util;
import org.junit.jupiter.api.Test;

class SignaturePreflightTest {

    private static final byte[] TRANSFER_SELECTOR =
        { (byte) 0xa9, 0x05, (byte) 0x9c, (byte) 0xbb };
    private static final String TRANSFER_SIG = "transfer(address,uint256)";

    @Test
    void skipLookup_returnsEmptyWithoutCallingBackend() {
        var result = SignaturePreflight.run(new byte[4], selector -> {
            throw new AssertionError("should not call lookup");
        }, true);
        assertFalse(result.hasMatches());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void verifiedHit_isIncludedInResult() {
        assertTrue(Keccak256Util.selectorMatches(TRANSFER_SIG, TRANSFER_SELECTOR));

        var result = SignaturePreflight.run(TRANSFER_SELECTOR,
            selector -> List.of(TRANSFER_SIG), false);
        assertTrue(result.hasMatches());
        assertEquals(List.of(TRANSFER_SIG), result.verified());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void lookupReturnsEmptyList_producesEmptyResult() {
        var result = SignaturePreflight.run(TRANSFER_SELECTOR, selector -> List.of(), false);
        assertFalse(result.hasMatches());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void lookupReturnsUnverifiedEntry_emitsDiagnosticNoMatch() {
        // API returns a signature whose Keccak selector does NOT match — diagnostic must fire
        var result = SignaturePreflight.run(TRANSFER_SELECTOR,
            selector -> List.of("doesNotMatch()"), false);
        assertFalse(result.hasMatches());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains("none verified"),
            "Expected 'none verified' in: " + result.diagnostics().get(0));
    }

    @Test
    void lookupThrowsRuntimeException_emitsDiagnosticNeverThrows() {
        // Network or non-2xx failure must not propagate — only a fixed diagnostic must be emitted
        var result = SignaturePreflight.run(TRANSFER_SELECTOR,
            selector -> { throw new RuntimeException("HTTP 429 — https://api.example.dev/v1/…"); },
            false);
        assertFalse(result.hasMatches());
        assertEquals(1, result.diagnostics().size());
        // Fixed message must be used — never the raw exception message (which may expose the URL)
        assertTrue(result.diagnostics().get(0).contains("unavailable"),
            "Expected 'unavailable' in: " + result.diagnostics().get(0));
        assertFalse(result.diagnostics().get(0).contains("HTTP 429"),
            "Diagnostic must not expose the raw exception message or request URL");
    }
}
