package me.tibetty.sigbrute.decode;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import me.tibetty.sigbrute.util.HexUtil;

/**
 * Input for the decoder. Parses two flavours of text:
 *
 * <p>
 * 1. Etherscan-style block, e.g.: Function: foo(uint256 a, tuple b, tuple[] c) MethodID:
 * 0xdeadbeef [0]: &lt;64 hex chars&gt; [1]: &lt;64 hex chars&gt;
 *
 * <p>
 * 2. Raw concatenated hex calldata (with or without "0x" prefix). The first 4 bytes are taken as
 * the selector; the rest is the body.
 *
 * <p>
 * When the Etherscan header is present its method name and top-level type list are surfaced so
 * the decoder can use them as a skeleton.
 */
public record CalldataInput(byte[] selector, byte[] body, String methodName,
    List<String> topLevelTypes, List<String> inlineTopLevelTypes) {

    /**
     * Same selector and body as this input, but without Etherscan / 4byte header hints. Use for
     * corpus verification so decode runs on calldata bytes only (heuristic body + nested decode).
     */
    public CalldataInput withoutSkeleton() {
        return new CalldataInput(selector, body, null, null, null);
    }

    /**
     * Same selector, body, and method name, but top-level types use opaque {@code tuple} /
     * {@code tuple[]} instead of inline {@code (T,...)} forms from a full 4byte signature.
     *
     * <p>
     * {@link #inlineTopLevelTypes} is left null; shallow decode uses only opaque top-level hints
     * ({@code tuple} / {@code tuple[]}) and heuristics — not the inline forms from {@code Function:}.
     */
    public CalldataInput withShallowSkeleton() {
        return new CalldataInput(selector, body, methodName,
            ShallowSkeletonHints.abstractTopLevelTypes(topLevelTypes), null);
    }

    private static final int REGEX_CASE_MULTILINE = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;

    private static final Pattern WORD_LINE = Pattern.compile("\\[(\\d+)]:\\s*([0-9a-f]{64})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_ID = Pattern
        .compile("^\\s*MethodID\\s*:\\s*(?:0x)?([0-9a-f]{8})\\s*$", REGEX_CASE_MULTILINE);
    private static final Pattern FUNCTION_NAME = Pattern.compile(
        "^\\s*Function\\s*:\\s*([_a-z]\\w*)\\s*\\(",
        REGEX_CASE_MULTILINE);

    private static final byte[] MISSING_SELECTOR = new byte[0];

    public static CalldataInput parse(String text) {
        var header = parseHeader(text);
        var selector = parseSelector(text);
        var fromWords = tryParseWordLines(text, selector, header);
        if (fromWords != null) {
            return fromWords;
        }

        return parseRawHex(text, header);
    }

    private static ParseHeader parseHeader(String text) {
        var fn = FUNCTION_NAME.matcher(text);
        if (!fn.find()) {
            return new ParseHeader(null, null);
        }

        var params = extractBalancedParams(text, fn.end());
        if (params == null) {
            return new ParseHeader(fn.group(1), null);
        }

        return new ParseHeader(fn.group(1), splitTopLevelTypes(params));
    }

    /**
     * Reads the parameter list inside {@code Function: name(...)} using balanced-paren scanning so
     * nested {@code (T,...)} tuple types are not truncated at the first {@code )}.
     */
    static String extractBalancedParams(String text, int paramsStart) {
        var depth = 1;
        for (var i = paramsStart; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(paramsStart, i);
                }
            }
        }
        return null;
    }

    private static byte[] parseSelector(String text) {
        var mid = METHOD_ID.matcher(text);
        if (!mid.find()) {
            return MISSING_SELECTOR;
        }

        return HexUtil.fromHex(mid.group(1));
    }

    private static CalldataInput tryParseWordLines(String text, byte[] selector,
        ParseHeader header) {
        var wl = WORD_LINE.matcher(text);
        var wordsByIndex = new ArrayList<byte[]>();
        var haveWordLines = false;
        while (wl.find()) {
            haveWordLines = true;
            var idx = Integer.parseInt(wl.group(1));
            var w = HexUtil.fromHex(wl.group(2));
            while (wordsByIndex.size() <= idx) {
                wordsByIndex.add(null);
            }
            wordsByIndex.set(idx, w);
        }

        if (!haveWordLines) {
            return null;
        }

        for (int i = 0; i < wordsByIndex.size(); i++) {
            if (wordsByIndex.get(i) == null) {
                throw new IllegalArgumentException("Missing word [" + i + "] in input");
            }
        }

        if (selector.length == 0) {
            throw new IllegalArgumentException(
                "Etherscan-style input has data words but no 'MethodID:' line");
        }

        var body = new ByteArrayOutputStream(wordsByIndex.size() * 32);
        for (byte[] w : wordsByIndex) {
            body.write(w, 0, w.length);
        }
        return new CalldataInput(selector, body.toByteArray(), header.methodName(),
            header.topLevelTypes(), null);
    }

    private static CalldataInput parseRawHex(String text, ParseHeader header) {
        var hex = extractContiguousHex(text);
        if (hex.length() < 8) {
            throw new IllegalArgumentException(
                "Input has no Etherscan-style words and no usable hex calldata");
        }

        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Raw hex calldata has odd length");
        }

        var raw = HexUtil.fromHex(hex);
        if (raw.length < 4) {
            throw new IllegalArgumentException("Raw hex calldata is shorter than 4-byte selector");
        }

        var sel = Arrays.copyOfRange(raw, 0, 4);
        var body = Arrays.copyOfRange(raw, 4, raw.length);
        if ((body.length & 31) != 0) {
            throw new IllegalArgumentException(
                "Calldata body length is not a multiple of 32 bytes: " + body.length);
        }

        return new CalldataInput(sel, body, header.methodName(), header.topLevelTypes(), null);
    }

    public static List<String> splitTopLevelTypes(String inner) {
        var out = new ArrayList<String>();
        var depth = 0;
        var cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                addTypeToken(out, cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }

        if (!cur.toString().isBlank()) {
            addTypeToken(out, cur.toString());
        }
        return out;
    }

    /** Strips an optional parameter name (e.g. "uint256 orderId" → "uint256"). */
    private static void addTypeToken(List<String> out, String raw) {
        var s = raw.trim();
        if (s.isEmpty()) {
            return;
        }

        var sp = s.lastIndexOf(' ');
        out.add(sp < 0 ? s : s.substring(0, sp).trim());
    }

    private static String extractContiguousHex(String text) {
        var s = text.strip();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        s = s.replaceAll("\\s+", "");
        if (s.isEmpty() || !s.matches("(?i)[0-9a-f]+")) {
            throw new IllegalArgumentException(
                "Input is neither Etherscan-style nor pure hex calldata");
        }

        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof CalldataInput that)) {
            return false;
        }

        return Arrays.equals(selector, that.selector) && Arrays.equals(body, that.body)
            && Objects.equals(methodName, that.methodName)
            && Objects.equals(topLevelTypes, that.topLevelTypes);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(selector);
        result = 31 * result + Arrays.hashCode(body);
        result = 31 * result + Objects.hashCode(methodName);
        result = 31 * result + Objects.hashCode(topLevelTypes);
        return result;
    }

    @Override
    public String toString() {
        return "CalldataInput[selector=" + Arrays.toString(selector) + ", body.length="
            + body.length + ", methodName=" + methodName + ", topLevelTypes=" + topLevelTypes + "]";
    }

    private record ParseHeader(String methodName, List<String> topLevelTypes) {
    }
}
