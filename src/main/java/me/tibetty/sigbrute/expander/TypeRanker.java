package me.tibetty.sigbrute.expander;

import static me.tibetty.sigbrute.decode.abi.AbiTypeSyntax.ADDRESS;
import static me.tibetty.sigbrute.decode.abi.AbiTypeSyntax.BYTES;
import static me.tibetty.sigbrute.decode.abi.AbiTypeSyntax.STRING;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks concrete ABI type names by their empirical frequency in the sigbank corpus
 * (github.com/tintinweb/sigbank, 924,117 on-chain function signatures, 1,704,303 total type
 * occurrences, 94 unique base types).
 *
 * <p>
 * Frequency data was produced by {@code sigbank/analyze_types.py} and is archived in {@code
 * src/main/resources/type_frequency.txt}. The ordering below matches the corpus rank exactly; types
 * absent from the corpus (all fixed-point types) are placed last.
 *
 * <h3>Why ranking matters</h3>
 *
 * {@link TypeExpander} expands wildcards in canonical order ({@code uint8, uint16, …, uint256}).
 * For {@code find_first} mode that is pessimal — {@code uint256} (41.6 % of all params) is tried
 * last. Ranking reorders the expanded list so the most-probable type is visited first, minimising
 * expected time-to-first-match.
 *
 * <h3>Effect on all-matches mode</h3>
 *
 * Harmless: every candidate is visited regardless; only the visit order changes.
 *
 * <h3>Where it is applied</h3>
 *
 * {@link me.tibetty.sigbrute.model.LeafArgSpec#expand()} sorts its expanded candidate list through
 * {@link #comparator()} before wrapping it in a {@link me.tibetty.sigbrute.util.Dimension}. No
 * other call site needs to know about this class.
 *
 * <h3>Notable corpus surprises (vs. hand-crafted priors)</h3>
 *
 * <ul>
 * <li>{@code string} is rank 3 (8.4 %) — far above {@code bool} (rank 5)
 * <li>{@code uint16} is rank 8 (1.4 %) — above {@code uint128} (rank 12)
 * <li>{@code uint24}, {@code int24}, {@code int32} are more common than {@code int64} or {@code
 *       int128}
 * <li>All {@code fixed} / {@code ufixed} types have zero corpus occurrences
 * </ul>
 */
public final class TypeRanker {

    private TypeRanker() {
    }

    /**
     * Ordinal = trial priority; 0 = highest (most frequent in corpus). Types absent from this map
     * receive {@link Integer#MAX_VALUE} and are tried last (stable tie-break: natural string
     * order).
     *
     * <p>
     * Source: sigbank corpus analysis, 924k signatures. Percentages rounded to 4 d.p.
     */
    private static final Map<String, Integer> RANK = buildRankMap();

    private static Map<String, Integer> buildRankMap() {
        // Listed strictly in descending corpus frequency.
        // TypeExpander.normalize() resolves them before any type reaches TypeRanker.
        String[] ordered = {

            // ── Group A: > 1 % ───────────────────────────────────────────────
            "uint256", // rank 1 41.5623 %
            ADDRESS,   // rank 2 30.0914 %
            STRING,    // rank 3 8.4458 %
            "bytes32", // rank 4 4.1953 %
            "bool",    // rank 5 3.5895 %
            "uint8",   // rank 6 3.2538 %
            BYTES,     // rank 7 2.6759 %

            // ── Group B: 0.1 %–1 % ──────────────────────────────────────────
            "uint16",  // rank 8 1.4117 %
            "uint32",  // rank 9 1.2269 %
            "uint64",  // rank 10 0.8162 %
            "uint128", // rank 12 0.4615 % (rank 11 = bare "uint", normalised → uint256)
            "int256",  // rank 13 0.3790 %
            "uint24",  // rank 14 0.2231 %
            "uint96",  // rank 15 0.2106 %

            // ── Group C: 0.01 %–0.1 % ───────────────────────────────────────
            "bytes4",  // rank 16 0.1203 %
            "int24",   // rank 17 0.1026 %
            "int128",  // rank 18 0.0644 %
            "uint48",  // rank 19 0.0642 %
            "int32",   // rank 20 0.0580 %
            "uint40",  // rank 21 0.0552 %
            "bytes16", // rank 22 0.0527 %
            "uint160", // rank 23 0.0464 %
            "uint80",  // rank 24 0.0343 %
            "int8",    // rank 25 0.0304 %
            "int64",   // rank 26 0.0297 %
            "uint112", // rank 27 0.0254 %
            "int16",   // rank 28 0.0226 %
            "bytes8",  // rank 29 0.0219 %
            "bytes1",  // rank 30 0.0163 %
            "bytes6",  // rank 31 0.0152 %
            "uint192", // rank 32 0.0146 %
            "bytes12", // rank 33 0.0137 %
            "bytes20", // rank 34 0.0135 %
            "uint120", // rank 35 0.0131 %
            "int96",   // rank 37 0.0118 % (rank 36 = bare "int", normalised → int256)
            "bytes3",  // rank 38 0.0104 %
            "uint72",  // rank 39 0.0102 %
            "uint88",  // rank 40 0.0102 %
            "bytes2",  // rank 41 0.0097 %
            "uint56",  // rank 42 0.0086 %

            // ── Group D: 0.001 %–0.01 % ─────────────────────────────────────
            "uint240", // rank 43 0.0077 %
            "uint224", // rank 44 0.0066 %
            "uint104", // rank 45 0.0065 %
            "uint248", // rank 46 0.0060 %
            "uint216", // rank 47 0.0055 %
            "bytes5",  // rank 48 0.0042 %
            "bytes24", // rank 49 0.0039 %
            "bytes10", // rank 50 0.0035 %
            "uint168", // rank 51 0.0033 %
            "uint208", // rank 53 0.0025 % (rank 52 = "byte", normalised → bytes1)
            "uint176", // rank 54 0.0025 %
            "uint232", // rank 55 0.0022 %
            "bytes15", // rank 56 0.0016 %
            "bytes9",  // rank 57 0.0014 %

            // ── Group E: < 0.001 % (corpus tail) ────────────────────────────
            "int192", "bytes22", "uint144", "bytes11", "uint152", "bytes7", "int56", "bytes14",
            "uint184", "int88", "bytes17", "uint200", "bytes23", "bytes30", "bytes29", "int40",
            "bytes31", "bytes19", "uint136", "int80", "bytes26", "int48", "bytes21", "bytes25",
            "int200", "int160", "int168", "int248", "int216", "int112", "int136", "int104",
            "int240",

            // ── Fixed-point (0 corpus occurrences; kept for completeness) ────
            "fixed128x18", "ufixed128x18",};

        var m = new HashMap<String, Integer>(ordered.length * 2);
        for (var i = 0; i < ordered.length; i++) {
            m.put(ordered[i], i);
        }
        return Map.copyOf(m);
    }

    /**
     * Frequency rank of a single concrete type name. Lower = more common = tried first in {@code
     * find_first} mode. Returns {@link Integer#MAX_VALUE} for unknown types.
     */
    public static int rankOf(String type) {
        return RANK.getOrDefault(type, Integer.MAX_VALUE);
    }

    /**
     * Comparator that orders types most-frequent-first. Ties (unknown types) are broken by natural
     * string order for stability.
     */
    public static Comparator<String> comparator() {
        return Comparator.comparingInt(TypeRanker::rankOf).thenComparing(Comparator.naturalOrder());
    }

    /**
     * Returns a new list containing the same elements as {@code types} but sorted
     * most-frequent-first. The original list is not modified.
     */
    public static List<String> rank(List<String> types) {
        return types.stream().sorted(comparator()).toList();
    }
}
