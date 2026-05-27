package me.tibetty.sigbrute.decode.abi;

import java.math.BigInteger;
import java.util.List;

/** Low-level ABI word access: slicing, uint decoding, and offset plausibility. */
public final class AbiCodec {

    private AbiCodec() {
    }

    public static boolean looksLikeOffsetAt(byte[] body, int slotIndex, int bodyLen,
        int headSize) {
        if (slotIndex < 0 || slotIndex * 32 >= bodyLen) {
            return false;
        }

        return isPlausibleOffset(uintOf(slice(body, slotIndex * 32, 32)), bodyLen, headSize);
    }

    public static int nextDynOffsetAfter(List<int[]> dynOffsets, int currentOffset, int bodyLen) {
        var next = bodyLen;
        for (int[] d : dynOffsets) {
            if (d[1] > currentOffset && d[1] < next) {
                next = d[1];
            }
        }
        return next;
    }
    public static int scanHeadSize(byte[] body) {
        var numWords = body.length / 32;
        var best = -1;
        for (var i = 0; i < numWords; i++) {
            var offset = candidateHeadOffset(uintOf(slice(body, i * 32, 32)), i, body.length);
            if (offset >= 0 && (best < 0 || offset < best)) {
                best = offset;
            }
        }
        return advanceHeadSizeIfTailLooksLikeOffset(body, best);
    }

    /**
     * Advances {@code headSize} when the first word of the tail is itself a larger plausible
     * head boundary, and the slots between the current and new head boundary confirm it by
     * containing at least one valid ABI offset relative to the new size.
     *
     * <p>This fixes the case where a static tuple field's {@code uint256} value coincidentally
     * satisfies all ABI-offset plausibility checks (aligned multiple of 32, within body length,
     * beyond slot position) and causes {@link #scanHeadSize} to return a head boundary that is
     * too small. In the correct interpretation those slots are static data and the actual dynamic
     * offsets form a cluster that begins further into the head.
     *
     * <p>Example: a 10-field tuple where fields 0–5 are static and fields 6–9 carry ABI offsets.
     * Field 5 has value {@code 0xC0 = 192 = 6×32} which looks like an offset, so
     * {@code scanHeadSize} returns 192. The first "tail" word is then 320 (the real first offset
     * at slot 6). Since 320 is also a plausible head size AND slot 7 (value 416) is a valid ABI
     * offset relative to headSize 320, we advance to 320 — the correct boundary.
     *
     * <p>Guard: the candidate {@code next} must be strictly less than {@code body.length} so that
     * at least one tail word exists after the extended head. The validation step (checking slots
     * strictly after {@code headSize / 32}) prevents false chaining when a bytes/string length
     * word happens to equal a larger multiple of 32 but the surrounding payload contains no
     * plausible offsets.
     */
    public static int advanceHeadSizeIfTailLooksLikeOffset(byte[] body, int headSize) {
        if (headSize < 0) {
            return headSize;
        }
        while (headSize + 32 <= body.length) {
            var next = nextHeadSizeFromTailWord(body, headSize);
            if (next < 0) {
                break;
            }
            headSize = next;
        }
        return headSize;
    }

    /**
     * If the first tail word is a larger plausible head boundary (validated by offsets in the
     * extended range), returns that boundary in bytes; otherwise {@code -1}.
     */
    public static int nextHeadSizeFromTailWord(byte[] body, int headSize) {
        var v = uintOf(slice(body, headSize, 32));
        if (v.signum() <= 0 || v.bitLength() > 31) {
            return -1;
        }
        var next = v.intValueExact();
        if (next % 32 != 0 || next <= headSize || next >= body.length) {
            return -1;
        }
        // Validate: at least one slot in (headSize/32, next/32) must carry a plausible ABI
        // offset relative to 'next'. The slot at headSize/32 (the word that triggered the
        // chain) is excluded to prevent bytes/string length words from self-validating.
        if (!hasPlausibleOffsetInRange(body, headSize / 32 + 1, next / 32, next)) {
            return -1;
        }
        return next;
    }

    /**
     * Returns {@code true} when any slot in {@code [fromSlot, toSlot)} (exclusive end) contains
     * a value that {@link #isPlausibleOffset} accepts for the given {@code headSize}.
     */
    public static boolean hasPlausibleOffsetInRange(byte[] body, int fromSlot, int toSlot,
        int headSize) {
        var numWords = body.length / 32;
        for (var i = fromSlot; i < toSlot && i < numWords; i++) {
            if (isPlausibleOffset(uintOf(slice(body, i * 32, 32)), body.length, headSize)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Offset in bytes from start of body, or -1 if the head word is not a plausible tail pointer.
     */
    public static int candidateHeadOffset(BigInteger value, int slotIndex, int bodyLen) {
        if (value.signum() <= 0 || value.bitLength() > 31) {
            return -1;
        }

        var offset = safeToInt(value, "scanHeadSize offset");
        if (offset % 32 != 0 || offset > bodyLen || offset <= slotIndex * 32) {
            return -1;
        }

        return offset;
    }

    public static boolean isPlausibleOffset(BigInteger v, int bodyLen, int headSize) {
        if (v.signum() < 0 || v.bitLength() > 31) {
            return false;
        }

        var off = safeToInt(v, "isPlausibleOffset");
        return off % 32 == 0 && off >= headSize && off + 32 <= bodyLen;
    }
    public static byte[] slice(byte[] src, int off, int len) {
        if (off < 0 || len < 0 || off + len > src.length) {
            throw new IllegalArgumentException(
                "slice out of range: off=" + off + " len=" + len + " src.len=" + src.length);
        }
        var out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    public static BigInteger uintOf(byte[] word) {
        return new BigInteger(1, word);
    }

    /**
     * Leading count word of a dynamic-array tail blob (first 32 bytes), as a non-negative
     * {@code int}.
     */
    public static int dynamicArrayCount(byte[] tail) {
        var v = uintOf(slice(tail, 0, 32));
        if (v.bitLength() > 31) {
            throw new IllegalArgumentException(
                "dynamic array element count exceeds int range: 0x" + v.toString(16));
        }
        return v.intValueExact();
    }

    public static int safeToInt(BigInteger v, String context) {
        if (v.bitLength() > 31) {
            throw new IllegalArgumentException(
                context + ": value 0x" + v.toString(16) + " exceeds int range");
        }

        return v.intValueExact();
    }
}
