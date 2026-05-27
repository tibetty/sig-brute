package me.tibetty.sigbrute.decode.abi;

import me.tibetty.sigbrute.decode.CalldataInput;

/** Solidity type-string helpers for skeleton slot counting and dynamic detection. */
public final class AbiTypeSyntax {

    public static final String BYTES = "bytes";
    public static final String STRING = "string";

    private AbiTypeSyntax() {
    }

    public static int staticInlineTupleHeadSlots(String type) {
        if (!isInlineTupleType(type)) {
            return 0;
        }
        var total = 0;
        for (var field : CalldataInput.splitTopLevelTypes(type.substring(1, type.length() - 1))) {
            var slots = staticSlotCount(field);
            if (slots == 0) {
                return 0;
            }
            total += slots;
        }
        return total;
    }

    /** {@code (T,...)} with no trailing {@code […]} brackets. */
    public static boolean isInlineTupleType(String type) {
        return type.startsWith("(") && type.endsWith(")") && !type.endsWith("[]");
    }

    public static boolean isOpaqueTupleType(String type) {
        return "tuple".equals(type);
    }

    public static boolean isTupleTypeHint(String type) {
        return isOpaqueTupleType(type) || isInlineTupleType(type);
    }
    public static boolean isDynamicBytesOrString(String type) {
        return type.equals(BYTES) || type.equals(STRING);
    }

    public static boolean isDynamicHint(String type) {
        if (isTupleTypeHint(type)) {
            return false; // tuple / inline (T,...) — decoded on the tuple path
        }

        if (isDynamicBytesOrString(type)) {
            return true;
        }

        if (type.endsWith("[]")) {
            return true;
        }

        var parts = splitArraySuffix(type);
        if (parts != null) {
            if (parts.suffix().contains("[]")) {
                return true;
            }

            return isDynamicHint(parts.base());
        }

        return false;
    }

    /** How many head slots a static type occupies. Returns 0 for dynamic types or "tuple". */
    public static int staticSlotCount(String type) {
        if (isInlineTupleType(type)) {
            return staticInlineTupleHeadSlots(type);
        }

        if (isOpaqueTupleType(type)) {
            return 0;
        }

        if (isDynamicBytesOrString(type)) {
            return 0;
        }

        if (isDynamicHint(type)) {
            return 0;
        }

        var parts = splitArraySuffix(type);
        if (parts != null) {
            var total = countFixedArrayElements(parts.suffix());
            if (total == 0) {
                return 0;
            }

            var baseSlots = staticSlotCount(parts.base());
            return baseSlots == 0 ? 0 : baseSlots * total;
        }

        if (isStaticPrimitive(type)) {
            return 1;
        }

        return 0;
    }

    /** Base type and trailing {@code [N]} / {@code []} dimensions, e.g. {@code uint256[3][4]}. */
    public record ArraySuffixParts(String base, String suffix) {
    }

    /**
     * Splits a type into base + bracket suffix without regex (avoids nested-quantifier
     * backtracking). Returns null when brackets are absent or malformed.
     */
    public static ArraySuffixParts splitArraySuffix(String type) {
        var bracket = type.indexOf('[');
        if (bracket < 0) {
            return null;
        }

        var base = type.substring(0, bracket);
        var suffix = type.substring(bracket);
        var pos = 0;
        while (pos < suffix.length()) {
            if (suffix.charAt(pos) != '[') {
                return null;
            }

            var end = suffix.indexOf(']', pos);
            if (end < 0) {
                return null;
            }

            var inner = suffix.substring(pos + 1, end);
            if (!inner.isEmpty() && !isAsciiDigits(inner)) {
                return null;
            }

            pos = end + 1;
        }
        return new ArraySuffixParts(base, suffix);
    }

    /**
     * Peels one trailing {@code […]} dimension that sits outside any {@code (...)} group, e.g.
     * {@code (uint8,address)[][]} → base {@code (uint8,address)[]}, suffix {@code []}.
     */
    public static ArraySuffixParts peelOutermostArrayDimension(String type) {
        if (!type.endsWith("]")) {
            return null;
        }

        var close = type.length() - 1;
        var open = findArrayOpenOutsideParens(type, close);
        if (open < 0 || !isValidArrayIndex(type, open, close)) {
            return null;
        }

        return new ArraySuffixParts(type.substring(0, open), type.substring(open));
    }

    /**
     * Like {@link #splitArraySuffix} but only peels suffix brackets that sit outside any
     * {@code (...)} group — needed for {@code (uint256,(string,uint8)[])[],bytes}[]}.
     */
    public static ArraySuffixParts splitOutermostArraySuffix(String type) {
        var i = type.length();
        while (i > 0 && type.charAt(i - 1) == ']') {
            var close = i - 1;
            var open = findArrayOpenOutsideParens(type, close);
            if (open < 0) {
                return null;
            }

            if (!isValidArrayIndex(type, open, close)) {
                return null;
            }

            i = open;
        }

        if (i == type.length()) {
            return null;
        }

        return new ArraySuffixParts(type.substring(0, i), type.substring(i));
    }

    public static int findArrayOpenOutsideParens(String type, int close) {
        var depth = 0;
        for (var j = close - 1; j >= 0; j--) {
            var c = type.charAt(j);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
            } else if (c == '[' && depth == 0) {
                return j;
            }
        }
        return -1;
    }

    public static boolean isValidArrayIndex(String type, int open, int close) {
        var inner = type.substring(open + 1, close);
        return inner.isEmpty() || isAsciiDigits(inner);
    }

    /** Product of fixed-size dimension lengths; 0 if any segment is {@code []} or invalid. */
    public static int countFixedArrayElements(String suffix) {
        var total = 1;
        var pos = 0;
        while (pos < suffix.length()) {
            if (suffix.charAt(pos) != '[') {
                return 0;
            }

            var end = suffix.indexOf(']', pos);
            if (end < 0) {
                return 0;
            }

            var n = suffix.substring(pos + 1, end);
            if (n.isEmpty()) {
                return 0;
            }

            total *= Integer.parseInt(n);
            pos = end + 1;
        }
        return total;
    }

    public static boolean isAsciiDigits(String s) {
        for (var i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isStaticPrimitive(String type) {
        return switch (type) {
            case "address", "bool" -> true;
            default -> isSizedInt(type, "uint") || isSizedInt(type, "int") || isBytesN(type)
                || isFixedMxN(type);
        };
    }

    public static boolean isSizedInt(String type, String prefix) {
        if (!type.startsWith(prefix)) {
            return false;
        }

        var rest = type.substring(prefix.length());
        if (rest.isEmpty()) {
            return true;
        }

        return isAsciiDigits(rest);
    }

    public static boolean isBytesN(String type) {
        if (!type.startsWith(BYTES)) {
            return false;
        }

        var rest = type.substring(BYTES.length());
        if (rest.isEmpty() || !isAsciiDigits(rest)) {
            return false;
        }

        var n = Integer.parseInt(rest);
        return n >= 1 && n <= 32;
    }

    public static boolean isFixedMxN(String type) {
        var start = fixedTypePrefixEnd(type);
        if (start < 0) {
            return false;
        }

        var x = type.indexOf('x', start);
        if (x <= start) {
            return false;
        }

        var mPart = type.substring(start, x);
        var nPart = type.substring(x + 1);
        return !mPart.isEmpty() && isAsciiDigits(mPart) && !nPart.isEmpty() && isAsciiDigits(nPart);
    }

    /** Index after {@code fixed} / {@code ufixed} prefix, or -1 if neither matches. */
    public static int fixedTypePrefixEnd(String type) {
        if (type.startsWith("ufixed")) {
            return 6;
        }

        if (type.startsWith("fixed")) {
            return 5;
        }

        return -1;
    }
}
