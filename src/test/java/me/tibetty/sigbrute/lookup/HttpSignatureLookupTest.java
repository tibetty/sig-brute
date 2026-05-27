package me.tibetty.sigbrute.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpSignatureLookupTest {

    @Test
    void parseTextSignatures_extractsMultipleEntries() {
        var json = """
            {"results":[{"text_signature":"transfer(address,uint256)"},\
            {"text_signature":"transfer(address,uint256)"}]}
            """;
        var sigs = HttpSignatureLookup.parseTextSignatures(json);
        assertEquals(2, sigs.size());
        assertEquals("transfer(address,uint256)", sigs.get(0));
    }

    @Test
    void parseTextSignatures_emptyWhenNoKey() {
        assertTrue(HttpSignatureLookup.parseTextSignatures("{}").isEmpty());
    }
}
