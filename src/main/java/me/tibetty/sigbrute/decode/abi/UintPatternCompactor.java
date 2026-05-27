package me.tibetty.sigbrute.decode.abi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Collapses consecutive uint candidate lists into compact YAML patterns. */
public final class UintPatternCompactor {

    public static final String UINT_WILDCARD = "uint*";

    private UintPatternCompactor() {
    }

    record UintCompactionPlan(Set<Integer> toRemove, List<String> patterns) {
    }

    public static List<String> compact(List<String> types) {
        var bits = collectConcreteUintBits(types);
        if (bits.isEmpty()) {
            return types;
        }

        var plan = planUintCompaction(maximalConsecutiveUintRuns(bits));
        if (plan.toRemove().isEmpty()) {
            return types;
        }

        return applyUintCompaction(types, plan);
    }

    public static List<Integer> collectConcreteUintBits(List<String> types) {
        var bits = new ArrayList<Integer>();
        for (var t : types) {
            if (isConcreteUint(t)) {
                bits.add(Integer.parseInt(t.substring(4)));
            }
        }
        bits.sort(null);
        return bits;
    }

    /**
     * Returns maximal consecutive uint bit-width runs (step = 8), each as {@code [first, last]}.
     *
     * @param bits sorted list of concrete uint bit-widths (e.g. [8, 16, 32, 256]); must be
     *             non-empty and sorted in ascending order (caller: {@link #collectConcreteUintBits}
     *             guarantees this via {@code bits.sort(null)})
     */
    public static List<int[]> maximalConsecutiveUintRuns(List<Integer> bits) {
        var runs = new ArrayList<int[]>();
        var s = bits.get(0);
        var e = bits.get(0);

        for (var i = 1; i < bits.size(); i++) {
            var b = bits.get(i);
            if (b == e + 8) {
                e = b;
            } else {
                runs.add(new int[]{s, e});
                s = b;
                e = b;
            }
        }

        runs.add(new int[]{s, e});
        return runs;
    }

    public static UintCompactionPlan planUintCompaction(List<int[]> runs) {
        var toRemove = new LinkedHashSet<Integer>();
        var patterns = new ArrayList<String>();
        for (var run : runs) {
            planSingleUintRun(run[0], run[1], toRemove, patterns);
        }
        return new UintCompactionPlan(toRemove, patterns);
    }

    public static void planSingleUintRun(int first, int last, Set<Integer> toRemove,
        List<String> patterns) {
        if (first == last) {
            return;
        }

        var atBottom = (first == 8);
        var atTop = (last == 256);
        if (!atBottom && !atTop) {
            return; // bounded on both sides — no single-token representation
        }

        addUintRange(toRemove, first, last);
        if (atBottom && atTop) {
            patterns.add(UINT_WILDCARD);
        } else if (atTop) {
            patterns.add("uint" + first + "+");
        } else {
            patterns.add("uint" + last + "-");
        }
    }

    public static List<String> applyUintCompaction(List<String> types, UintCompactionPlan plan) {
        var result = new ArrayList<String>();
        for (var t : types) {
            if (isConcreteUint(t) && plan.toRemove().contains(Integer.parseInt(t.substring(4)))) {
                continue;
            }
            result.add(t);
        }
        result.addAll(plan.patterns());
        return result;
    }

    /** Adds every multiple of 8 in [{@code first}, {@code last}] to {@code set}. */
    public static void addUintRange(Set<Integer> set, int first, int last) {
        for (var n = first; n <= last; n += 8) {
            set.add(n);
        }
    }

    /** True for bare concrete uint types like {@code uint8}, {@code uint256} (no +/-/*). */
    public static boolean isConcreteUint(String t) {
        if (!t.startsWith("uint")) {
            return false;
        }

        var rest = t.substring(4);
        return !rest.isEmpty() && AbiTypeSyntax.isAsciiDigits(rest);
    }
}
