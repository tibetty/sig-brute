package me.tibetty.sigbrute.decode.abi;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.util.HexUtil;
import me.tibetty.sigbrute.util.Keccak256Util;

/**
 * Minimal ABI encoder for unit tests.
 *
 * <p>
 * Encodes a subset of Solidity ABI types and emits Etherscan-format calldata text that
 * {@link CalldataInput#parse} accepts. Supports:
 *
 * <ul>
 * <li><b>Static (32-byte inline):</b> {@code uint256}, {@code bool}, {@code address}, {@code
 *       bytesN}
 * <li><b>Dynamic (offset pointer + payload):</b> {@code bytes}, {@code string}, {@code T[]} with
 * static elements via {@link #staticArray}
 * </ul>
 *
 * <p>
 * Not intended for production use — correctness is prioritised over completeness.
 */
public final class AbiTestEncoder {

    private AbiTestEncoder() {
    }

    // ── Value hierarchy ───────────────────────────────────────────────────────

    /**
     * A fully encoded ABI argument value. Inner records {@code Val.Static} and {@code Val.Dynamic}
     * represent the two ABI head encodings.
     */
    public sealed interface Val permits Val.Static, Val.Dynamic {

        /** Returns {@code true} when this value requires an offset pointer in the head. */
        boolean dynamic();

        /** Fixed-size 32-byte value encoded inline in the head section. */
        record Static(byte[] word) implements Val {
            /** Validates that the word is exactly 32 bytes. */
            public Static {
                if (word.length != 32) {
                    throw new IllegalArgumentException("Static word must be exactly 32 bytes");
                }
            }

            @Override
            public boolean dynamic() {
                return false;
            }
        }

        /**
         * Variable-size value whose head entry is a {@code uint256} offset. The payload must be
         * padded to a multiple of 32 bytes.
         */
        record Dynamic(byte[] payload) implements Val {
            /** Validates that the payload length is a multiple of 32 bytes. */
            public Dynamic {
                if (payload.length % 32 != 0) {
                    throw new IllegalArgumentException(
                        "Dynamic payload length must be a multiple of 32 bytes, got "
                            + payload.length);
                }
            }

            @Override
            public boolean dynamic() {
                return true;
            }
        }
    }

    // ── Static-value factories ────────────────────────────────────────────────

    /** Right-aligned {@code uint8}..{@code uint256} (non-negative). */
    public static Val.Static uint(long value) {
        return uint(BigInteger.valueOf(value));
    }

    /** Right-aligned {@code uint8}..{@code uint256} (non-negative). */
    public static Val.Static uint(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("uint value must be non-negative");
        }
        var enc = value.toByteArray();
        // BigInteger.toByteArray() may add a leading 0x00 sign byte. Strip leading
        // zeros but keep at least one byte so that BigInteger.ZERO encodes as 0x00.
        var start = 0;
        while (start < enc.length - 1 && enc[start] == 0) {
            start++;
        }
        var len = enc.length - start;
        if (len > 32) {
            throw new IllegalArgumentException("Value exceeds 256 bits");
        }
        var word = new byte[32];
        System.arraycopy(enc, start, word, 32 - len, len);
        return new Val.Static(word);
    }

    /**
     * Right-aligned {@code bool}: {@code false} → 32 zeros; {@code true} → 31 zeros + {@code 0x01}.
     */
    public static Val.Static bool(boolean value) {
        var word = new byte[32];
        if (value) {
            word[31] = 1;
        }
        return new Val.Static(word);
    }

    /**
     * Right-aligned {@code address}. {@code hex20} must be a 20-byte hex string (with or without
     * {@code 0x} prefix). ABI encoding: 12 zero bytes + address.
     */
    public static Val.Static address(String hex20) {
        var addr = HexUtil.fromHex(hex20);
        if (addr.length != 20) {
            throw new IllegalArgumentException(
                "Address must be exactly 20 bytes, got " + addr.length);
        }
        var word = new byte[32];
        System.arraycopy(addr, 0, word, 12, 20);
        return new Val.Static(word);
    }

    /**
     * Left-aligned {@code bytesN} (N = 1..32). The first N bytes of {@code hex} are placed at the
     * start of the 32-byte word; the remainder is zero-padded.
     */
    public static Val.Static bytesN(String hex, int n) {
        if (n < 1 || n > 32) {
            throw new IllegalArgumentException("N must be in [1, 32], got " + n);
        }
        var src = HexUtil.fromHex(hex);
        var word = new byte[32];
        System.arraycopy(src, 0, word, 0, Math.min(n, src.length));
        return new Val.Static(word);
    }

    // ── Dynamic-value factories ───────────────────────────────────────────────

    /**
     * Dynamic {@code bytes} value. Payload = 32-byte length word + {@code data} padded to the next
     * multiple of 32.
     */
    public static Val.Dynamic bytes(byte[] data) {
        var paddedLen = ((data.length + 31) / 32) * 32;
        var payload = new byte[32 + paddedLen];
        putUint(payload, 0, data.length);
        System.arraycopy(data, 0, payload, 32, data.length);
        return new Val.Dynamic(payload);
    }

    /** Dynamic {@code string} value — UTF-8, same ABI encoding as {@code bytes}. */
    public static Val.Dynamic string(String s) {
        return bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Dynamic {@code T[]} primitive array whose elements are all static (32 bytes each). Payload =
     * 32-byte count word + concatenated element words.
     */
    public static Val.Dynamic staticArray(Val.Static... elements) {
        var payload = new byte[32 + elements.length * 32];
        putUint(payload, 0, elements.length);
        for (int i = 0; i < elements.length; i++) {
            System.arraycopy(elements[i].word(), 0, payload, 32 + i * 32, 32);
        }
        return new Val.Dynamic(payload);
    }

    /**
     * One element of a dynamic {@code (T,...)}[] array with statically packed tuple fields in the
     * tail (count word + consecutive field words).
     */
    public static Val.Dynamic inlineTupleStaticArray(Val.Static... fields) {
        var body = concat(fields);
        var paddedLen = ((body.length + 31) / 32) * 32;
        var payload = new byte[32 + paddedLen];
        putUint(payload, 0, 1);
        System.arraycopy(body, 0, payload, 32, body.length);
        return new Val.Dynamic(payload);
    }

    /**
     * Dynamic array whose elements are separate dynamic blobs (ABI offset table). Used for nested
     * {@code T[][]} outer dimensions.
     */
    public static Val.Dynamic offsetIndexedArray(Val.Dynamic... elements) {
        var tableBytes = 32 + elements.length * 32;
        var payloadBytes = 0;
        for (var element : elements) {
            payloadBytes += element.payload().length;
        }
        var payload = new byte[tableBytes + payloadBytes];
        putUint(payload, 0, elements.length);
        var dataPos = tableBytes;
        for (var i = 0; i < elements.length; i++) {
            putUint(payload, 32 + i * 32, dataPos - 32);
            System.arraycopy(elements[i].payload(), 0, payload, dataPos,
                elements[i].payload().length);
            dataPos += elements[i].payload().length;
        }
        return new Val.Dynamic(payload);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Concatenates the words of all static values into a single byte array. Useful for constructing
     * a raw calldata body that has no dynamic tail.
     */
    public static byte[] concat(Val.Static... vals) {
        var out = new byte[vals.length * 32];
        for (int i = 0; i < vals.length; i++) {
            System.arraycopy(vals[i].word(), 0, out, i * 32, 32);
        }
        return out;
    }

    // ── Calldata formatter ────────────────────────────────────────────────────

    /**
     * Encodes a complete function call and returns Etherscan-format text that {@link
     * CalldataInput#parse} accepts.
     *
     * <p>
     * The {@code Function:} line enables the skeleton-guided decode path. CalldataInput's regex
     * does not allow nested parentheses in the argument list, so tuple-typed args must be written
     * as {@code "tuple"} or {@code "tuple[]"}. Pass {@code null} to omit the header and force
     * pure-heuristic decoding.
     *
     * @param canonicalSig
     *            full canonical signature (used to compute the selector), e.g. {@code
     *     "transfer(address,uint256)"}
     * @param skeletonSig
     *            Etherscan-style function line with no nested parens, e.g. {@code
     *     "swap(uint256,tuple[])"} — or {@code null}
     * @param args
     *            argument values in parameter order
     * @return Etherscan-format calldata text
     */
    public static String etherscanDump(String canonicalSig, String skeletonSig, Val... args) {
        var selector = Arrays.copyOf(Keccak256Util.hash(canonicalSig), 4);

        // ── Encode head + tail ────────────────────────────────────────────────
        var headSize = args.length * 32;
        var head = new byte[args.length][32];
        var tailChunks = new ArrayList<byte[]>();
        var tailByteOffset = headSize;

        for (int i = 0; i < args.length; i++) {
            Val v = args[i];
            if (v instanceof Val.Static s) {
                head[i] = s.word();
            } else if (v instanceof Val.Dynamic d) {
                putUint(head[i], 0, tailByteOffset);
                tailChunks.add(d.payload());
                tailByteOffset += d.payload().length;
            }
        }

        // ── Assemble 32-byte words ────────────────────────────────────────────
        var words = new ArrayList<byte[]>(args.length);
        for (byte[] w : head) {
            words.add(w);
        }
        for (byte[] chunk : tailChunks) {
            for (int off = 0; off < chunk.length; off += 32) {
                words.add(Arrays.copyOfRange(chunk, off, off + 32));
            }
        }

        // ── Format as Etherscan text ──────────────────────────────────────────
        var sb = new StringBuilder();
        if (skeletonSig != null) {
            sb.append("Function: ").append(skeletonSig).append('\n');
            sb.append('\n');
        }
        sb.append("MethodID: 0x").append(HexUtil.toHex(selector)).append('\n');
        for (int i = 0; i < words.size(); i++) {
            sb.append('[').append(i).append("]: ").append(HexUtil.toHex(words.get(i))).append('\n');
        }
        return sb.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Writes a non-negative {@code long} value right-aligned (big-endian) into the 32-byte word at
     * {@code dest[wordStart..wordStart+31]}. Only the lower 8 bytes are written; the upper 24 bytes
     * are left unchanged (expected to be 0).
     */
    private static void putUint(byte[] dest, int wordStart, long value) {
        for (int i = 0; i < 8; i++) {
            dest[wordStart + 31 - i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
    }
}
