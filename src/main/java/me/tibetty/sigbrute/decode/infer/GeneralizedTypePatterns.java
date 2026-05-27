package me.tibetty.sigbrute.decode.infer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Collapses verbose {@link TypeInferrer} candidate lists into compact floor patterns
 * ({@code uintN+}, {@code bytesN+}, {@code int*}, {@code fixed*}, …) for heuristic-search YAML.
 */
public final class GeneralizedTypePatterns {

    private static final String UINT_WILDCARD = "uint*";
    private static final String INT_WILDCARD = "int*";
    private static final String BYTES_WILDCARD = "bytes*";
    private static final String FIXED_WILDCARD = "fixed*";
    private static final String UFIXED_WILDCARD = "ufixed*";

    private static final Pattern UINT_FLOOR = Pattern.compile("^uint(\\d+)\\+$");
    private static final Pattern INT_FLOOR = Pattern.compile("^int(\\d+)\\+$");
    private static final Pattern BYTES_FLOOR = Pattern.compile("^bytes(\\d+)\\+$");
    private static final Pattern CONCRETE_BYTES = Pattern.compile("^bytes(\\d+)$");
    private static final Pattern CONCRETE_FIXED = Pattern.compile("^fixed(\\d+)x(\\d+)$");
    private static final Pattern CONCRETE_UFIXED = Pattern.compile("^ufixed(\\d+)x(\\d+)$");
    private static final Pattern FIXED_STAR_N = Pattern.compile("^fixed\\*x(\\d+)$");
    private static final Pattern FIXED_M_STAR = Pattern.compile("^fixed(\\d+)x\\*$");
    private static final Pattern UFIXED_STAR_N = Pattern.compile("^ufixed\\*x(\\d+)$");
    private static final Pattern UFIXED_M_STAR = Pattern.compile("^ufixed(\\d+)x\\*$");

    private GeneralizedTypePatterns() {
    }

    public static List<String> generalize(List<String> raw) {
        if (raw.isEmpty()) {
            return raw;
        }

        var other = new LinkedHashSet<String>();
        var uintFamily = new ArrayList<String>();
        var intFamily = new ArrayList<String>();
        var bytesFamily = new ArrayList<String>();
        var fixedFamily = new ArrayList<String>();
        var ufixedFamily = new ArrayList<String>();

        for (var t : raw) {
            if (isUintFamilyToken(t)) {
                uintFamily.add(t);
            } else if (isIntFamilyToken(t)) {
                intFamily.add(t);
            } else if (isBytesFamilyToken(t)) {
                bytesFamily.add(t);
            } else if (t.startsWith("ufixed")) {
                ufixedFamily.add(t);
            } else if (t.startsWith("fixed")) {
                fixedFamily.add(t);
            } else {
                other.add(t);
            }
        }

        var out = new ArrayList<String>();
        out.addAll(other);
        appendUintPattern(out, uintFamily);
        appendIntPattern(out, intFamily);
        appendBytesPattern(out, bytesFamily);
        appendFixedPointPattern(out, fixedFamily, "fixed", FIXED_WILDCARD);
        appendFixedPointPattern(out, ufixedFamily, "ufixed", UFIXED_WILDCARD);
        return List.copyOf(out);
    }

    /**
     * Keeps compact floor/wildcard patterns and adds a few greedy companions that materially widen
     * sig-brute search without restoring full per-bit-width uint lists.
     */
    public static List<String> widenForYamlSearch(List<String> raw, List<String> compact) {
        var out = new LinkedHashSet<>(compact);
        var hasBytesFamily = compact.stream().anyMatch(GeneralizedTypePatterns::isBytesFamilyToken);
        if (hasBytesFamily) {
            if (raw.contains("string")) {
                out.add("string");
            }
            if (raw.contains("bytes")) {
                out.add("bytes");
            }
        }
        return List.copyOf(out);
    }

    private static void appendUintPattern(List<String> out, List<String> uintFamily) {
        if (uintFamily.isEmpty()) {
            return;
        }

        var uintFloor = Integer.MAX_VALUE;
        var hasUintWildcard = false;
        for (var t : uintFamily) {
            if (UINT_WILDCARD.equals(t)) {
                hasUintWildcard = true;
            } else if (isConcreteUint(t)) {
                uintFloor = Math.min(uintFloor, Integer.parseInt(t.substring(4)));
            } else {
                var floor = uintFloorBits(t);
                if (floor >= 0) {
                    uintFloor = Math.min(uintFloor, floor);
                }
            }
        }
        out.add(resolveUintPattern(hasUintWildcard, uintFloor));
    }

    private static void appendIntPattern(List<String> out, List<String> intFamily) {
        if (intFamily.isEmpty()) {
            return;
        }

        var intFloor = Integer.MAX_VALUE;
        var hasIntWildcard = false;
        for (var t : intFamily) {
            if (INT_WILDCARD.equals(t)) {
                hasIntWildcard = true;
            } else if ("int256".equals(t)) {
                intFloor = Math.min(intFloor, 256);
            } else if (t.matches("int\\d+")) {
                intFloor = Math.min(intFloor, Integer.parseInt(t.substring(3)));
            } else {
                var m = INT_FLOOR.matcher(t);
                if (m.matches()) {
                    intFloor = Math.min(intFloor, Integer.parseInt(m.group(1)));
                }
            }
        }
        if (hasIntWildcard || intFloor < 8) {
            out.add(INT_WILDCARD);
        } else if (intFloor < Integer.MAX_VALUE) {
            out.add(intFloor >= 256 ? "int256" : "int" + intFloor + "+");
        }
    }

    private static void appendBytesPattern(List<String> out, List<String> bytesFamily) {
        if (bytesFamily.isEmpty()) {
            return;
        }

        var hasDynamicBytes = false;
        var hasBytesWildcard = false;
        var minConcrete = Integer.MAX_VALUE;
        var hasBytes32 = false;

        for (var t : bytesFamily) {
            if ("bytes".equals(t)) {
                hasDynamicBytes = true;
            } else if (BYTES_WILDCARD.equals(t)) {
                hasBytesWildcard = true;
            } else if ("bytes32".equals(t)) {
                hasBytes32 = true;
                minConcrete = Math.min(minConcrete, 32);
            } else {
                var floor = bytesFloorLength(t);
                if (floor >= 0) {
                    minConcrete = Math.min(minConcrete, floor);
                } else {
                    var m = CONCRETE_BYTES.matcher(t);
                    if (m.matches()) {
                        minConcrete = Math.min(minConcrete, Integer.parseInt(m.group(1)));
                    }
                }
            }
        }

        if (hasDynamicBytes) {
            out.add("bytes");
        }
        if (hasBytesWildcard) {
            out.add(BYTES_WILDCARD);
            return;
        }
        if (minConcrete == Integer.MAX_VALUE) {
            return;
        }
        if (minConcrete >= 32 && hasBytes32) {
            out.add("bytes32");
            return;
        }
        out.add("bytes" + minConcrete + "+");
    }

    private static void appendFixedPointPattern(List<String> out, List<String> family,
        String prefix, String wildcard) {
        if (family.isEmpty()) {
            return;
        }

        if (family.stream().anyMatch(wildcard::equals)) {
            out.add(wildcard);
            return;
        }

        var concreteM = new LinkedHashSet<Integer>();
        var concreteN = new LinkedHashSet<Integer>();
        var starN = new LinkedHashSet<Integer>();
        var mStar = new LinkedHashSet<Integer>();

        for (var t : family) {
            if (wildcard.equals(t)) {
                continue;
            }
            var concrete = prefix.equals("fixed")
                ? CONCRETE_FIXED.matcher(t)
                : CONCRETE_UFIXED.matcher(t);
            if (concrete.matches()) {
                concreteM.add(Integer.parseInt(concrete.group(1)));
                concreteN.add(Integer.parseInt(concrete.group(2)));
                continue;
            }
            var sn = prefix.equals("fixed")
                ? FIXED_STAR_N.matcher(t)
                : UFIXED_STAR_N.matcher(t);
            if (sn.matches()) {
                starN.add(Integer.parseInt(sn.group(1)));
                continue;
            }
            var ms = prefix.equals("fixed")
                ? FIXED_M_STAR.matcher(t)
                : UFIXED_M_STAR.matcher(t);
            if (ms.matches()) {
                mStar.add(Integer.parseInt(ms.group(1)));
            }
        }

        if (starN.size() == 1 && concreteM.isEmpty() && mStar.isEmpty()) {
            out.add(prefix + "*x" + starN.iterator().next());
            return;
        }
        if (mStar.size() == 1 && concreteN.isEmpty() && starN.isEmpty()) {
            out.add(prefix + mStar.iterator().next() + "x*");
            return;
        }
        if (concreteM.size() == 1 && concreteN.size() == 1 && starN.isEmpty() && mStar.isEmpty()) {
            var m = concreteM.iterator().next();
            var n = concreteN.iterator().next();
            out.add(prefix + m + "x" + n);
            return;
        }
        if (concreteN.size() == 1 && starN.isEmpty() && mStar.isEmpty() && !concreteM.isEmpty()) {
            out.add(prefix + "*x" + concreteN.iterator().next());
            return;
        }
        if (concreteM.size() == 1 && concreteN.isEmpty() && starN.isEmpty() && mStar.isEmpty()) {
            out.add(prefix + concreteM.iterator().next() + "x*");
            return;
        }

        out.add(wildcard);
    }

    private static String resolveUintPattern(boolean hasUintWildcard, int uintFloor) {
        if (hasUintWildcard || uintFloor < 8) {
            return UINT_WILDCARD;
        }
        if (uintFloor >= 256) {
            return "uint256";
        }
        return "uint" + uintFloor + "+";
    }

    private static boolean isUintFamilyToken(String t) {
        return t.startsWith("uint");
    }

    private static boolean isIntFamilyToken(String t) {
        return t.startsWith("int") && !t.startsWith("uint");
    }

    private static boolean isBytesFamilyToken(String t) {
        return t.equals("bytes") || t.startsWith("bytes");
    }

    private static boolean isConcreteUint(String t) {
        if (!t.startsWith("uint")) {
            return false;
        }
        var rest = t.substring(4);
        return !rest.isEmpty() && rest.chars().allMatch(Character::isDigit);
    }

    /** Minimum bit-width floor from {@code uintN+}; {@code -1} if not a uint floor pattern. */
    static int uintFloorBits(String t) {
        if (UINT_WILDCARD.equals(t)) {
            return 8;
        }
        var m = UINT_FLOOR.matcher(t);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /** Minimum byte length from {@code bytesN+}; {@code -1} if not a bytes floor pattern. */
    static int bytesFloorLength(String t) {
        if (BYTES_WILDCARD.equals(t)) {
            return 1;
        }
        var m = BYTES_FLOOR.matcher(t);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }
}
