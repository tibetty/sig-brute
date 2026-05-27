package me.tibetty.sigbrute.decode.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OffsetTableTest {

    @Test
    void parseMonotonic_validTwoElementTable() {
        var body = new byte[32 + 32 + 32 + 64];
        body[31] = 2;
        body[32 + 31] = 64;
        body[32 + 32 + 31] = 96;
        var offsets = OffsetTable.parseMonotonic(body, 2, body.length - 32);
        assertEquals(2, offsets.length);
        assertEquals(64, offsets[0]);
        assertEquals(96, offsets[1]);
    }

    @Test
    void parseMonotonic_nonMonotonicReturnsEmpty() {
        var body = new byte[32 + 64];
        body[31] = 1;
        body[32 + 31] = 96;
        assertEquals(0, OffsetTable.parseMonotonic(body, 1, body.length - 32).length);
    }

    @Test
    void allElementsLookLikeBytes_detectsLengthPrefixedElements() {
        var body = new byte[32 + 32 + 32 + 32];
        body[31] = 1;
        body[32 + 31] = 32;
        body[32 + 32 + 31] = 4;
        var offsets = new int[]{32};
        assertTrue(OffsetTable.allElementsLookLikeBytes(body, 1, offsets));
    }
}
