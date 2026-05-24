package me.tibetty.sigbrute.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import me.tibetty.sigbrute.model.LeafArgSpec;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.util.Keccak256Util;
import org.junit.jupiter.api.Test;

class SearchEngineTest {

    @Test
    void findFirstReturnsSingleMatch() {
        var selector = selectorBytes("transfer(address,uint256)");
        var config = new SearchConfig(selector, List.of("transfer"),
            List.of(new LeafArgSpec(List.of("address")), new LeafArgSpec(List.of("uint256"))), 1,
            true);
        var results = new SearchEngine(config,
            new PrintStream(OutputStream.nullOutputStream())).search();
        assertEquals(1, results.size());
        assertTrue(results.contains("transfer(address,uint256)"));
    }

    @Test
    void findAllReturnsMatchingSignatures() {
        var selector = selectorBytes("transfer(address,uint256)");
        var config = new SearchConfig(selector, List.of("transfer", "noSuchMethod"),
            List.of(new LeafArgSpec(List.of("address")), new LeafArgSpec(List.of("uint256"))), 1,
            false);
        var results = new SearchEngine(config,
            new PrintStream(OutputStream.nullOutputStream())).search();
        assertEquals(1, results.size());
        assertTrue(results.contains("transfer(address,uint256)"));
        assertFalse(results.contains("noSuchMethod(address,uint256)"));
    }

    @Test
    void noMatchReturnsEmptyList() {
        var config = new SearchConfig(new byte[]{0, 0, 0, 0}, List.of("noMatch"),
            List.of(new LeafArgSpec(List.of("uint256"))), 1, false);
        var results = new SearchEngine(config,
            new PrintStream(OutputStream.nullOutputStream())).search();
        assertTrue(results.isEmpty());
    }

    private static byte[] selectorBytes(String sig) {
        var hash = Keccak256Util.hash(sig);
        return new byte[]{hash[0], hash[1], hash[2], hash[3]};
    }
}
