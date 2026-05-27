package me.tibetty.sigbrute.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import me.tibetty.sigbrute.util.Keccak256Util;
import org.junit.jupiter.api.Test;

class SignaturePreflightTest {

    @Test
    void skipLookup_returnsEmptyWithoutCallingBackend() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var hits = SignaturePreflight.run(new byte[4], selector -> {
            throw new AssertionError("should not call lookup");
        }, new PrintStream(out), new PrintStream(err), true);
        assertTrue(hits.isEmpty());
    }

    @Test
    void verifiedHitsPrintedToStdout() {
        var selector = new byte[]{ (byte) 0xa9, 0x05, (byte) 0x9c, (byte) 0xbb };
        var sig = "transfer(address,uint256)";
        assertTrue(Keccak256Util.selectorMatches(sig, selector));

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var hits = SignaturePreflight.run(selector, s -> java.util.List.of(sig),
            new PrintStream(out), new PrintStream(err), false);
        assertEquals(1, hits.size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("transfer(address,uint256)"));
    }
}
