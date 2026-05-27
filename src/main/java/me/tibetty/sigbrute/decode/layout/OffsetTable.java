package me.tibetty.sigbrute.decode.layout;

import java.math.BigInteger;
import me.tibetty.sigbrute.decode.abi.AbiCodec;

/** ABI dynamic-array offset tables in tail blobs (monotonic element pointers). */
public final class OffsetTable {

    private OffsetTable() {
    }

    /**
     * Parses the offset table after the leading count word. Returns an empty array when the table
     * is missing or not monotonic.
     */
    public static int[] parseMonotonic(byte[] body, int count, int remaining) {
        var elemOffsets = new int[count];
        var prev = BigInteger.valueOf(count * 32L);
        for (var i = 0; i < count; i++) {
            var offsetWord = AbiCodec.uintOf(AbiCodec.slice(body, 32 + i * 32, 32));
            if (!isValidElementOffset(offsetWord, prev, count, remaining)) {
                return new int[0];
            }

            var offset = offsetWord.intValueExact();
            elemOffsets[i] = offset;
            prev = BigInteger.valueOf(offset);
        }

        return elemOffsets;
    }

    public static boolean isValidElementOffset(BigInteger offsetWord, BigInteger prev, int count,
        int remaining) {
        if (offsetWord.bitLength() > 31) {
            return false;
        }

        var offset = offsetWord.intValueExact();
        if (offset % 32 != 0 || offset < count * 32 || offset + 32 > remaining) {
            return false;
        }

        return BigInteger.valueOf(offset).compareTo(prev) >= 0;
    }

    /**
     * {@code true} when every element looks like length-prefixed {@code bytes} (not a tuple offset
     * table).
     */
    public static boolean allElementsLookLikeBytes(byte[] body, int count, int[] elemOffsets) {
        for (var i = 0; i < count; i++) {
            var from = 32 + elemOffsets[i];
            var to = (i + 1 < count) ? 32 + elemOffsets[i + 1] : body.length;
            var elemLen = to - from;
            if (elemLen < 32) {
                return false;
            }

            var lengthWord = AbiCodec.uintOf(AbiCodec.slice(body, from, 32));
            if (lengthWord.bitLength() > 31) {
                return false;
            }

            var expectedSize = 32 + paddedLength(lengthWord);
            if (expectedSize != elemLen) {
                return false;
            }
        }

        return true;
    }

    public static int paddedLength(BigInteger lengthWord) {
        var length = lengthWord.intValueExact();
        return ((length + 31) / 32) * 32;
    }
}
