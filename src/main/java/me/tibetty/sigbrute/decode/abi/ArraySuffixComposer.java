package me.tibetty.sigbrute.decode.abi;

import me.tibetty.sigbrute.decode.DecodedArg;
/**
 * Merges Solidity array bracket suffixes onto {@link DecodedArg} trees after one dynamic dimension
 * has been decoded. Keeps suffix algebra in one place (e.g. inner {@code []} + outer {@code [][]}
 * → {@code [][]}, not {@code []}).
 */
public final class ArraySuffixComposer {

    private ArraySuffixComposer() {
    }

    /**
     * Attaches {@code dimension} ({@code []} or {@code [N]}) to a decoded element. When
     * {@code dimension} already includes inner suffixes (e.g. {@code [][]}), the longer suffix wins
     * over a single {@code []} on a tuple element.
     */
    public static DecodedArg attach(DecodedArg decoded, String dimension) {
        if (dimension.isEmpty()) {
            return decoded;
        }
        if (decoded instanceof DecodedArg.Tuple t) {
            var merged = mergeSuffix(t.arraySuffix(), dimension);
            return new DecodedArg.Tuple(merged, t.fields(), t.comment());
        }
        if (decoded instanceof DecodedArg.PrimArray pa) {
            return new DecodedArg.PrimArray(pa.arraySuffix() + dimension, pa.baseCandidates(),
                pa.comment());
        }
        return decoded;
    }

    /**
     * Combines an inner suffix already on a decoded tuple/array with an outer peel dimension.
     */
    public static String mergeSuffix(String inner, String outer) {
        if (inner.isEmpty()) {
            return outer;
        }
        if (outer.isEmpty()) {
            return inner;
        }
        if (outer.length() > inner.length()) {
            return outer;
        }
        if (outer.equals(inner)) {
            return inner + outer;
        }
        return inner + outer;
    }
}
