package me.tibetty.sigbrute.expander;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public final class TypeExpander {

    private static final String UINT = "uint";
    private static final String INT = "int";
    private static final String BYTES = "bytes";
    private static final String FIXED = "fixed";
    private static final String UFIXED = "ufixed";

    private TypeExpander() {
    }

    /**
     * Expands a pattern into concrete ABI type strings.
     *
     * <p>
     * Supported wildcards (* suffix is preserved and appended to each expansion): uint* → uint8,
     * uint16, ..., uint256 uintN+ → uintN, uint(N+8), ..., uint256 (floor: value needs ≥ N bits)
     * uintN- → uint8, uint16, ..., uintN (ceiling: value fits in ≤ N bits) int* → int8, int16, ...,
     * int256 bytes* → bytes1, bytes2, ..., bytes32 fixed* → fixed8x1, fixed8x2, ..., fixed256x80
     * (all valid M×N) ufixed* → ufixed8x1, ..., ufixed256x80 fixed*xN → fixed8xN, fixed16xN, ... (M
     * wildcard, fixed N) fixedMx* → fixedMx1, fixedMx2, ... (fixed M, N wildcard)
     *
     * <p>
     * Examples: "uint*" → ["uint8", ..., "uint256"] (32 types) "uint64+" → ["uint64", "uint72",
     * ..., "uint256"] (25 types) "uint64-" → ["uint8", "uint16", ..., "uint64"] ( 8 types) "fixed"
     * → ["fixed128x18"] "fixed*x18" → ["fixed8x18", ..., "fixed256x18"] "fixed128x*" →
     * ["fixed128x1", ..., "fixed128x80"]
     *
     * <p>
     * The floor (+) is emitted automatically by the decoder when leading zeros in a slot
     * constrain the minimum bit width. The ceiling (-) is a user-declared hint in the YAML — the
     * decoder cannot infer an upper bound from the encoded bytes alone (any uint type zero-pads its
     * value to 32 bytes identically).
     */
    public static List<String> expand(String pattern) {
        var uintBounded = tryExpandUintBounded(pattern);
        if (!uintBounded.isEmpty()) {
            return uintBounded;
        }

        var intBounded = tryExpandIntBounded(pattern);
        if (!intBounded.isEmpty()) {
            return intBounded;
        }

        if (pattern.startsWith(UFIXED)) {
            return expandFixedPoint(UFIXED, pattern);
        }

        if (pattern.startsWith(FIXED)) {
            return expandFixedPoint(FIXED, pattern);
        }

        return expandStarWildcard(pattern);
    }

    /**
     * uintN+ floor (decoder) or uintN- ceiling (YAML hint). Returns an empty list when the pattern
     * is not a valid bounded uint form so callers can fall through.
     *
     * <p>
     * Also handles array forms: {@code uintN+[]} expands to {@code [uintN[], uint(N+8)[], ...,
     * uint256[]]}; similarly for {@code uintN-[M]}.
     */
    private static List<String> tryExpandUintBounded(String pattern) {
        if (!pattern.startsWith(UINT)) {
            return List.of();
        }
        // Strip optional array suffix ([], [N]) before inspecting the bound marker.
        var bracket = pattern.indexOf('[');
        var arraySuffix = bracket >= 0 ? pattern.substring(bracket) : "";
        var base = bracket >= 0 ? pattern.substring(0, bracket) : pattern;

        if (base.endsWith("+")) {
            var expanded = expandUintRange(base, true);
            if (expanded.isEmpty()) {
                return List.of();
            }

            if (arraySuffix.isEmpty()) {
                return expanded;
            }

            return expanded.stream().map(t -> t + arraySuffix).toList();
        }

        if (base.endsWith("-")) {
            var expanded = expandUintRange(base, false);
            if (expanded.isEmpty()) {
                return List.of();
            }

            if (arraySuffix.isEmpty()) {
                return expanded;
            }

            return expanded.stream().map(t -> t + arraySuffix).toList();
        }

        return List.of();
    }

    /**
     * intN+ floor or intN- ceiling. Returns an empty list when the pattern is not a valid bounded
     * int form so callers can fall through.
     *
     * <p>
     * Follows exactly the same semantics as {@link #tryExpandUintBounded} but for the signed
     * {@code intN} family. Also handles array forms: {@code intN+[]} → {@code [intN[], …,
     * int256[]]}.
     */
    private static List<String> tryExpandIntBounded(String pattern) {
        // Must start with "int" but NOT "uint" (already handled above).
        if (!pattern.startsWith(INT) || pattern.startsWith(UINT)) {
            return List.of();
        }
        var bracket = pattern.indexOf('[');
        var arraySuffix = bracket >= 0 ? pattern.substring(bracket) : "";
        var base = bracket >= 0 ? pattern.substring(0, bracket) : pattern;

        if (base.endsWith("+")) {
            var expanded = expandIntRange(base, true);
            if (expanded.isEmpty()) {
                return List.of();
            }
            return arraySuffix.isEmpty() ? expanded
                : expanded.stream().map(t -> t + arraySuffix).toList();
        }

        if (base.endsWith("-")) {
            var expanded = expandIntRange(base, false);
            if (expanded.isEmpty()) {
                return List.of();
            }
            return arraySuffix.isEmpty() ? expanded
                : expanded.stream().map(t -> t + arraySuffix).toList();
        }

        return List.of();
    }

    private static List<String> expandIntRange(String pattern, boolean floor) {
        var numStr = pattern.substring(INT.length(), pattern.length() - 1);
        if (numStr.isEmpty()) {
            return List.of();
        }
        try {
            var boundBits = Integer.parseInt(numStr);
            if (boundBits < 8 || boundBits > 256 || boundBits % 8 != 0) {
                return List.of();
            }
            var sizes = validMs();
            sizes = floor ? sizes.filter(m -> m >= boundBits) : sizes.filter(m -> m <= boundBits);
            return sizes.mapToObj(m -> INT + m).toList();
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    private static List<String> expandUintRange(String pattern, boolean floor) {
        var numStr = pattern.substring(UINT.length(), pattern.length() - 1);
        if (numStr.isEmpty()) {
            return List.of();
        }

        try {
            var boundBits = Integer.parseInt(numStr);
            if (boundBits < 8 || boundBits > 256 || boundBits % 8 != 0) {
                return List.of();
            }

            var sizes = validMs();
            sizes = floor ? sizes.filter(m -> m >= boundBits) : sizes.filter(m -> m <= boundBits);
            return sizes.mapToObj(m -> UINT + m).toList();
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    private static List<String> expandStarWildcard(String pattern) {
        var starIdx = pattern.indexOf('*');
        if (starIdx < 0) {
            return List.of(normalize(pattern));
        }

        var base = pattern.substring(0, starIdx);
        var suffix = pattern.substring(starIdx + 1);

        return switch (base) {
            case UINT -> validMs().mapToObj(m -> UINT + m + suffix).toList();
            case INT -> validMs().mapToObj(m -> INT + m + suffix).toList();
            case BYTES -> IntStream.rangeClosed(1, 32).mapToObj(n -> BYTES + n + suffix).toList();
            default -> throw new IllegalArgumentException(
                "Unknown wildcard base '" + base + "' in pattern: " + pattern);
        };
    }

    private static List<String> expandFixedPoint(String prefix, String pattern) {
        var rest = pattern.substring(prefix.length());
        var starIdx = rest.indexOf('*');
        if (starIdx < 0) {
            return List.of(normalize(pattern));
        }

        return expandFixedPointWildcard(prefix, pattern, rest.substring(0, starIdx),
            rest.substring(starIdx + 1));
    }

    private static List<String> expandFixedPointWildcard(String prefix, String pattern,
        String beforeStar, String afterStar) {
        if (beforeStar.isEmpty()) {
            return expandFixedBareStar(prefix, afterStar, pattern);
        }

        if (beforeStar.endsWith("x")) {
            return expandFixedWithKnownM(prefix, beforeStar, afterStar, pattern);
        }

        throw unsupportedFixedPattern(pattern);
    }

    private static List<String> expandFixedBareStar(String prefix, String afterStar,
        String pattern) {
        if (afterStar.isEmpty() || afterStar.startsWith("[")) {
            return allFixedPairs(prefix, afterStar);
        }

        if (!afterStar.startsWith("x")) {
            throw unsupportedFixedPattern(pattern);
        }

        return expandFixedWildcardN(prefix, afterStar.substring(1), pattern);
    }

    private static List<String> expandFixedWildcardN(String prefix, String tail, String pattern) {
        if (tail.isEmpty()) {
            return allFixedPairs(prefix, "");
        }

        var bracket = tail.indexOf('[');
        var nPart = bracket >= 0 ? tail.substring(0, bracket) : tail;
        if (nPart.contains("*")) {
            throw unsupportedFixedPattern(pattern);
        }

        var arraySuffix = bracket >= 0 ? tail.substring(bracket) : "";
        var n = parseFixedN(nPart, pattern);
        return validMs().mapToObj(m -> prefix + m + "x" + n + arraySuffix).toList();
    }

    private static List<String> expandFixedWithKnownM(String prefix, String beforeStar,
        String afterStar, String pattern) {
        var m = parseFixedM(beforeStar.substring(0, beforeStar.length() - 1), pattern);
        return validNs().mapToObj(n -> prefix + m + "x" + n + afterStar).toList();
    }

    private static IllegalArgumentException unsupportedFixedPattern(String pattern) {
        return new IllegalArgumentException("Unsupported fixed-point pattern: " + pattern);
    }

    private static List<String> allFixedPairs(String prefix, String suffix) {
        var out = new ArrayList<String>();
        validMs().forEach(m -> validNs().forEach(n -> out.add(prefix + m + "x" + n + suffix)));
        return out;
    }

    private static IntStream validMs() {
        return IntStream.iterate(8, m -> m <= 256, m -> m + 8);
    }

    private static IntStream validNs() {
        return IntStream.rangeClosed(1, 80);
    }

    private static int parseFixedM(String mStr, String pattern) {
        var m = Integer.parseInt(mStr);
        if (m < 8 || m > 256 || m % 8 != 0) {
            throw new IllegalArgumentException("Invalid fixed M in pattern: " + pattern);
        }

        return m;
    }

    private static int parseFixedN(String nStr, String pattern) {
        var n = Integer.parseInt(nStr);
        if (n < 1 || n > 80) {
            throw new IllegalArgumentException("Invalid fixed N in pattern: " + pattern);
        }

        return n;
    }

    private static String normalize(String type) {
        var bracket = type.indexOf('[');
        if (bracket >= 0) {
            return normalizeBase(type.substring(0, bracket)) + type.substring(bracket);
        }

        return normalizeBase(type);
    }

    private static String normalizeBase(String base) {
        return switch (base) {
            case UINT -> "uint256";
            case INT -> "int256";
            case "byte" -> "bytes1";
            case FIXED -> "fixed128x18";
            case UFIXED -> "ufixed128x18";
            default -> base;
        };
    }
}
