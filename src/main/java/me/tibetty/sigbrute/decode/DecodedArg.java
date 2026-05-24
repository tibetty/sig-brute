package me.tibetty.sigbrute.decode;

import java.util.List;

/**
 * One decoded argument position. Mirrors the three shapes the sig-brute YAML schema accepts:
 * bare-list leaf, "[]"/"[N]" primitive-array, "()"/"()[]"/"()[N]" tuple.
 */
public sealed interface DecodedArg permits DecodedArg.Leaf, DecodedArg.PrimArray, DecodedArg.Tuple {

    /** Optional one-line comment describing the decoded value (rendered in YAML). */
    String comment();

    record Leaf(List<String> candidates, String comment) implements DecodedArg {
    }

    record PrimArray(String arraySuffix, List<String> baseCandidates,
        String comment) implements DecodedArg {
    }

    record Tuple(String arraySuffix, List<DecodedArg> fields,
        String comment) implements DecodedArg {
    }
}
