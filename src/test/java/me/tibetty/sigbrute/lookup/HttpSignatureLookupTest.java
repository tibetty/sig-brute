package me.tibetty.sigbrute.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void lookupException_carriesStatusCode() {
        // Non-2xx responses must expose the status code for caller decision-making
        var ex = new LookupException(429);
        assertEquals(429, ex.statusCode());
        assertTrue(ex.getMessage().contains("429"),
            "getMessage() must include the HTTP status: " + ex.getMessage());
    }

    @Test
    void lookupException_isRuntimeException() {
        // SignaturePreflight.run() catches LookupException; it must be a RuntimeException
        var ex = new LookupException(503);
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void lookupException_statusCodeAccessorReturnsCode() {
        // Verify the accessor method returns exactly the value passed to the constructor
        var ex = new LookupException(503);
        assertEquals(503, ex.statusCode());
    }
}
