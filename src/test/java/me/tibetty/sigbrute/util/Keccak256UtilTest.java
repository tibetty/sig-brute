package me.tibetty.sigbrute.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Keccak256UtilTest {

    @Test
    void emptyStringKnownAnswer() {
        var expected = hexToBytes(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
        assertArrayEquals(expected, Keccak256Util.hash(""));
    }

    @Test
    void transferSelectorKnownAnswer() {
        var expected = hexToBytes(
            "a9059cbb2ab09eb219583f4a59a5d0623ade346d962bcd4e46b11da047c9049b");
        assertArrayEquals(expected, Keccak256Util.hash("transfer(address,uint256)"));
    }

    @Test
    void selectorMatchesKnownAnswer() {
        assertTrue(Keccak256Util.selectorMatches("transfer(address,uint256)",
            new byte[]{(byte) 0xa9, (byte) 0x05, (byte) 0x9c, (byte) 0xbb}));
    }

    @Test
    void selectorMatchesNoMatch() {
        assertFalse(
            Keccak256Util.selectorMatches("transfer(address,uint256)", hexToBytes("70a08231")));
    }

    @Test
    void selectorMatchesFirstByteDiffers() {
        assertFalse(Keccak256Util.selectorMatches("transfer(address,uint256)",
            new byte[]{(byte) 0xa8, (byte) 0x05, (byte) 0x9c, (byte) 0xbb}));
    }

    @Test
    void selectorMatchesShortSelectorThrows() {
        assertThrows(ArrayIndexOutOfBoundsException.class,
            () -> Keccak256Util.selectorMatches("transfer(address,uint256)",
                new byte[]{(byte) 0xa9, (byte) 0x05, (byte) 0x9c}));
    }

    private static byte[] hexToBytes(String hex) {
        var len = hex.length();
        var out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}
