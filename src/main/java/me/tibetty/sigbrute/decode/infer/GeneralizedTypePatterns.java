package me.tibetty.sigbrute.decode.infer;

import static me.tibetty.sigbrute.decode.abi.AbiTypeSyntax.BYTES;
import static me.tibetty.sigbrute.decode.abi.AbiTypeSyntax.STRING;

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
    private static final String FIXED_PREFIX = "fixed";
    private static final String UFIXED_PREFIX = "ufixed";
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
            } else if (t.startsWith(UFIXED_PREFIX)) {
                ufixedFamily.add(t);
            } else if (t.startsWith(FIXED_PREFIX)) {
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
        appendFixedPointPattern(out, fixedFamily, FIXED_PREFIX, FIXED_WILDCARD);
        appendFixedPointPattern(out, ufixedFamily, UFIXED_PREFIX, UFIXED_WILDCARD);
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
            if (raw.contains(STRING)) {
                out.add(STRING);
            }
            if (raw.contains(BYTES)) {
                out.add(BYTES);
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
        var stats = collectBytesPatternStats(bytesFamily);
        emitBytesPattern(out, stats);
    }

    private record BytesPatternStats(boolean hasDynamicBytes, boolean hasBytesWildcard,
        int minConcrete, boolean hasBytes32) {
    }

    private static BytesPatternStats collectBytesPatternStats(List<String> bytesFamily) {
        var hasDynamicBytes = false;
        var hasBytesWildcard = false;
        var minConcrete = Integer.MAX_VALUE;
        var hasBytes32 = false;
        for (var t : bytesFamily) {
            if (BYTES.equals(t)) {
                hasDynamicBytes = true;
            } else if (BYTES_WILDCARD.equals(t)) {
                hasBytesWildcard = true;
            } else if ("bytes32".equals(t)) {
                hasBytes32 = true;
                minConcrete = Math.min(minConcrete, 32);
            } else {
                minConcrete = Math.min(minConcrete, concreteBytesLengthOrMax(t));
            }
        }
        return new BytesPatternStats(hasDynamicBytes, hasBytesWildcard, minConcrete, hasBytes32);
    }

    private static int concreteBytesLengthOrMax(String token) {
        var floor = bytesFloorLength(token);
        if (floor >= 0) {
            return floor;
        }
        var m = CONCRETE_BYTES.matcher(token);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        return Integer.MAX_VALUE;
    }

    private static void emitBytesPattern(List<String> out, BytesPatternStats stats) {
        if (stats.hasDynamicBytes()) {
            out.add(BYTES);
        }
        if (stats.hasBytesWildcard()) {
            out.add(BYTES_WILDCARD);
            return;
        }
        if (stats.minConcrete() == Integer.MAX_VALUE) {
            return;
        }
        if (stats.minConcrete() >= 32 && stats.hasBytes32()) {
            out.add("bytes32");
            return;
        }
        out.add(BYTES + stats.minConcrete() + "+");
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

        var buckets = collectFixedPointBuckets(family, prefix, wildcard);
        out.add(resolveFixedPointPattern(prefix, wildcard, buckets));
    }

    private record FixedPointBuckets(
        LinkedHashSet<Integer> concreteM,
        LinkedHashSet<Integer> concreteN,
        LinkedHashSet<Integer> starN,
        LinkedHashSet<Integer> mStar
    ) {
    }

    private static FixedPointBuckets collectFixedPointBuckets(List<String> family, String prefix,
        String wildcard) {
        var concreteM = new LinkedHashSet<Integer>();
        var concreteN = new LinkedHashSet<Integer>();
        var starN = new LinkedHashSet<Integer>();
        var mStar = new LinkedHashSet<Integer>();
        for (var t : family) {
            collectFixedPointToken(t, prefix, wildcard, concreteM, concreteN, starN, mStar);
        }
        return new FixedPointBuckets(concreteM, concreteN, starN, mStar);
    }

    private static void collectFixedPointToken(String token, String prefix, String wildcard,
        LinkedHashSet<Integer> concreteM, LinkedHashSet<Integer> concreteN,
        LinkedHashSet<Integer> starN, LinkedHashSet<Integer> mStar) {
        if (wildcard.equals(token)) {
            return;
        }
        var concrete = concretePatternFor(prefix).matcher(token);
        if (concrete.matches()) {
            concreteM.add(Integer.parseInt(concrete.group(1)));
            concreteN.add(Integer.parseInt(concrete.group(2)));
            return;
        }
        var sn = starNPatternFor(prefix).matcher(token);
        if (sn.matches()) {
            starN.add(Integer.parseInt(sn.group(1)));
            return;
        }
        var ms = mStarPatternFor(prefix).matcher(token);
        if (ms.matches()) {
            mStar.add(Integer.parseInt(ms.group(1)));
        }
    }

    private static Pattern concretePatternFor(String prefix) {
        return FIXED_PREFIX.equals(prefix) ? CONCRETE_FIXED : CONCRETE_UFIXED;
    }

    private static Pattern starNPatternFor(String prefix) {
        return FIXED_PREFIX.equals(prefix) ? FIXED_STAR_N : UFIXED_STAR_N;
    }

    private static Pattern mStarPatternFor(String prefix) {
        return FIXED_PREFIX.equals(prefix) ? FIXED_M_STAR : UFIXED_M_STAR;
    }

    private static String resolveFixedPointPattern(String prefix, String wildcard,
        FixedPointBuckets buckets) {
        var concreteM = buckets.concreteM();
        var concreteN = buckets.concreteN();
        var starN = buckets.starN();
        var mStar = buckets.mStar();
        if (starN.size() == 1 && concreteM.isEmpty() && mStar.isEmpty()) {
            return prefix + "*x" + starN.iterator().next();
        }
        if (mStar.size() == 1 && concreteN.isEmpty() && starN.isEmpty()) {
            return prefix + mStar.iterator().next() + "x*";
        }
        if (concreteM.size() == 1 && concreteN.size() == 1 && starN.isEmpty() && mStar.isEmpty()) {
            return prefix + concreteM.iterator().next() + "x" + concreteN.iterator().next();
        }
        if (concreteN.size() == 1 && starN.isEmpty() && mStar.isEmpty() && !concreteM.isEmpty()) {
            return prefix + "*x" + concreteN.iterator().next();
        }
        if (concreteM.size() == 1 && concreteN.isEmpty() && starN.isEmpty() && mStar.isEmpty()) {
            return prefix + concreteM.iterator().next() + "x*";
        }
        return wildcard;
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
        return t.equals(BYTES) || t.startsWith(BYTES);
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
