package me.tibetty.sigbrute.decode.emit;

import java.util.List;

import me.tibetty.sigbrute.decode.DecodedArg;

/**
 * Renders the decoded arg tree as a single Solidity-style prototype string, using the first
 * candidate per leaf. Output is purely structural — argument count, tuple/array nesting, and array
 * element type — and is meant as a one-line summary above the emitted YAML, not as a final
 * signature.
 */
public final class PrototypeRenderer {

    private PrototypeRenderer() {
    }

    public static String render(String methodName, List<DecodedArg> args) {
        var sb = new StringBuilder();
        sb.append(methodName == null || methodName.isBlank() ? "<fn>" : methodName);
        sb.append('(');

        for (var i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(renderArg(args.get(i)));
        }

        sb.append(')');
        return sb.toString();
    }

    /** Prototype from shallow skeleton type strings ({@code tuple}, {@code tuple[]}, scalars). */
    public static String renderFromShallowTypes(String methodName, List<String> shallowTopLevelTypes) {
        var sb = new StringBuilder();
        sb.append(methodName == null || methodName.isBlank() ? "<fn>" : methodName);
        sb.append('(');
        for (var i = 0; i < shallowTopLevelTypes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(shallowTopLevelTypes.get(i));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String renderArg(DecodedArg a) {
        if (a instanceof DecodedArg.Leaf leaf) {
            return pickConcrete(firstOrDefault(leaf.candidates()));
        }

        if (a instanceof DecodedArg.PrimArray pa) {
            var base = firstOrDefault(pa.baseCandidates());
            return pickConcrete(base) + pa.arraySuffix();
        }

        if (a instanceof DecodedArg.Tuple t) {
            var sb = new StringBuilder();
            sb.append('(');
            for (var i = 0; i < t.fields().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(renderArg(t.fields().get(i)));
            }
            sb.append(')').append(t.arraySuffix());
            return sb.toString();
        }

        throw new IllegalStateException("Unknown DecodedArg subtype: " + a);
    }

    private static String firstOrDefault(List<String> candidates) {
        return candidates.isEmpty() ? "uint256" : candidates.get(0);
    }

    /** Maps wildcards to their canonical concrete form for the prototype display. */
    private static String pickConcrete(String pattern) {
        // uintN+ → display the floor (minimum type), e.g. "uint64+" → "uint64"
        if (pattern.length() > 5 && pattern.startsWith("uint") && pattern.endsWith("+")) {
            return pattern.substring(0, pattern.length() - 1);
        }
        // uintN- → display the ceiling (maximum type), e.g. "uint64-" → "uint64"
        if (pattern.length() > 5 && pattern.startsWith("uint") && pattern.endsWith("-")) {
            return pattern.substring(0, pattern.length() - 1);
        }

        return switch (pattern) {
            case "uint*" -> "uint256";
            case "int*" -> "int256";
            case "bytes*" -> "bytes32";
            case "fixed*" -> "fixed128x18";
            case "ufixed*" -> "ufixed128x18";
            default -> pattern;
        };
    }
}
