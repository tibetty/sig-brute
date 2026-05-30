package me.tibetty.sigbrute.decode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.decode.skeleton.SkeletonTypes;
import me.tibetty.sigbrute.expander.TypeExpander;
import me.tibetty.sigbrute.util.HexUtil;

/**
 * Shallow skeleton mode ({@code decode --shallow-skeleton}).
 *
 * <p>Block explorers often show {@code tuple} / {@code tuple[]} at the top level while hiding
 * inner field types. Shallow skeleton keeps RE-friendly opaque top-level names in emitted YAML,
 * but still uses the original {@code Function:} line in two limited ways:
 *
 * <ul>
 *   <li><b>Layout</b> — head-slot demand and tuple span resolution ({@link #layoutHints})
 *   <li><b>Structure decode</b> — tuple nesting and array suffixes inside opaque payloads
 *       ({@link #dynamicDecodeType}, {@link #opaqueTupleFieldHints}) when interior inline hints
 *       are supplied; {@code decode --shallow-skeleton} passes none (calldata heuristics only)
 * </ul>
 */
public final class ShallowSkeletonHints {

    static final String OPAQUE_TUPLE = "tuple";

    private ShallowSkeletonHints() {
    }

    /**
     * Returns a copy of {@code topLevelTypes} where each top-level inline tuple becomes
     * {@code tuple} or {@code tuple[]} (array suffix preserved).
     */
    public static List<String> abstractTopLevelTypes(List<String> topLevelTypes) {
        if (topLevelTypes == null || topLevelTypes.isEmpty()) {
            return List.of();
        }
        return topLevelTypes.stream().map(ShallowSkeletonHints::abstractTopLevelType).toList();
    }

    /**
     * Merges shallow opaque hints with original inline types for head-slot demand and tuple-span
     * resolution (inline tuple parameters keep their static head width).
     */
    public static List<String> layoutHints(List<String> shallow, List<String> inline) {
        if (inline == null || inline.isEmpty() || inline.size() != shallow.size()) {
            return shallow;
        }
        var merged = new ArrayList<String>(shallow.size());
        for (var i = 0; i < shallow.size(); i++) {
            var inl = inline.get(i);
            merged.add(SkeletonTypes.isInlineTuple(inl) ? inl : shallow.get(i));
        }
        return merged;
    }

    /**
     * Replaces a single top-level inline tuple type with {@code tuple} plus any outer array suffix.
     * Non-tuple types are returned unchanged.
     */
    public static String abstractTopLevelType(String type) {
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(type);
        if (parts == null) {
            return isInlineTupleBase(type) ? OPAQUE_TUPLE : type;
        }
        if (isInlineTupleBase(parts.base())) {
            return OPAQUE_TUPLE + parts.suffix();
        }
        return type;
    }

    private static boolean isInlineTupleBase(String type) {
        return SkeletonTypes.isInlineTuple(type);
    }

    /** True when a shallow hint names an opaque {@code tuple} (optional array suffix only). */
    public static boolean isOpaqueTopLevelTupleHint(String shallowType) {
        if (shallowType == null) {
            return false;
        }
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(shallowType);
        var base = parts != null ? parts.base() : shallowType;
        return OPAQUE_TUPLE.equals(base);
    }

    /**
     * True when layout uses only explorer-style opaque names ({@code tuple}, {@code tuple[]},
     * primitives, …) with no inline {@code (T,...)} types — skeleton-only decode.
     *
     * <p>Enables {@link me.tibetty.sigbrute.decode.skeleton.SkeletonOnlyStaticTuplePartitioner},
     * {@link me.tibetty.sigbrute.decode.skeleton.SkeletonArrayDecoder} opaque {@code tuple[]}
     * routing ({@link me.tibetty.sigbrute.decode.skeleton.SkeletonTypeKind#OPAQUE_TUPLE_ARRAY}),
     * and score-gated greedy tuple-body heuristics.
     */
    public static boolean isSkeletonOnlyLayout(List<String> layoutHint) {
        if (layoutHint == null || layoutHint.isEmpty()) {
            return false;
        }
        for (var type : layoutHint) {
            if (SkeletonTypes.isInlineTuple(type)) {
                return false;
            }
        }
        return true;
    }

    /** Array suffix from a shallow type, e.g. {@code tuple[]} → {@code []}. */
    public static String arraySuffixFromShallowType(String shallowType) {
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(shallowType);
        return parts != null ? parts.suffix() : "";
    }

    /**
     * Type string for decoding a dynamic top-level argument: inline tuple or inline {@code (T,)[]}
     * when present, otherwise the shallow hint name.
     */
    public static String dynamicDecodeType(List<String> inline, int argIndex, String shallowType) {
        if (inline == null || inline.isEmpty() || argIndex >= inline.size()) {
            return shallowType;
        }
        var inl = inline.get(argIndex);
        if (inl == null || inl.isEmpty()) {
            return shallowType;
        }
        if (SkeletonTypes.isInlineTuple(inl)) {
            return inl;
        }
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(inl);
        if (parts != null && SkeletonTypes.isInlineTuple(parts.base())) {
            return inl;
        }
        return shallowType;
    }

    /**
     * Per-field inline types for decoding an opaque top-level {@code tuple} body, or empty when
     * the inline hint is not an inline tuple.
     */
    public static List<String> opaqueTupleFieldHints(List<String> inline, int argIndex) {
        if (inline == null || inline.isEmpty() || argIndex >= inline.size()) {
            return List.of();
        }
        var inl = inline.get(argIndex);
        if (SkeletonTypes.isInlineTuple(inl)) {
            return SkeletonTypes.inlineFieldTypes(inl);
        }
        return List.of();
    }

    /**
     * When an inline tuple has a single field that is a fixed-size array (e.g. {@code bytes32[67]}
     * inside {@code (bytes32[67])}), returns that field type. Used to decode N head slots as one
     * {@code T[N]} field instead of N separate tuple fields.
     */
    public static String singletonFixedArrayField(List<String> fieldHints) {
        if (fieldHints == null || fieldHints.size() != 1) {
            return null;
        }
        var field = fieldHints.get(0);
        var parts = AbiTypeSyntax.splitArraySuffix(field);
        if (parts == null || parts.suffix().isEmpty() || parts.suffix().contains("[]")) {
            return null;
        }
        if (AbiTypeSyntax.staticSlotCount(field) <= 1) {
            return null;
        }
        return field;
    }

    private static final Pattern HEX_WORD_IN_COMMENT = Pattern.compile("0x[0-9a-fA-F]+");

    /**
     * Re-infers leaf type candidates from calldata words referenced in decode comments. Used when
     * emitting shallow YAML so inner fields stay heuristic ({@code uint*}, …) while tuple nesting
     * still comes from structure decode. Skeleton-pinned concrete types (e.g. {@code bytes32} from
     * the {@code Function:} line) are kept when heuristics would not expand to cover them.
     */
    public static DecodedArg widenOpaqueRegionForEmit(DecodedArg arg) {
        if (arg instanceof DecodedArg.Leaf leaf) {
            return widenLeafForEmit(leaf);
        }
        if (arg instanceof DecodedArg.PrimArray pa) {
            return new DecodedArg.PrimArray(pa.arraySuffix(), pa.baseCandidates(), pa.comment());
        }
        if (arg instanceof DecodedArg.Tuple t) {
            var fields = t.fields().stream().map(ShallowSkeletonHints::widenOpaqueRegionForEmit).toList();
            return new DecodedArg.Tuple(t.arraySuffix(), fields, t.comment());
        }
        return arg;
    }

    private static DecodedArg widenLeafForEmit(DecodedArg.Leaf leaf) {
        var word = wordFromDecodeComment(leaf.comment());
        if (word.length == 0) {
            return leaf;
        }
        var inferred = TypeInferrer.inferStatic(word);
        if (inferred.equals(leaf.candidates())) {
            return leaf;
        }
        return new DecodedArg.Leaf(mergeSkeletonWithInferred(leaf.candidates(), inferred), leaf.comment());
    }

    /**
     * Heuristic candidates first; prepend any concrete skeleton-pinned type not already covered by
     * wildcard expansion (so {@code bytes32} is not dropped when calldata looks like a small uint).
     */
    static List<String> mergeSkeletonWithInferred(List<String> skeleton, List<String> inferred) {
        var merged = new ArrayList<>(inferred);
        for (var pin : skeleton) {
            if (isConcretePinnedType(pin) && !isCoveredByCandidatePatterns(pin, inferred)) {
                merged.add(0, pin);
            }
        }
        return merged.stream().distinct().toList();
    }

    private static boolean isConcretePinnedType(String type) {
        return !type.contains("*") && !type.contains("+");
    }

    private static boolean isCoveredByCandidatePatterns(String concrete, List<String> patterns) {
        for (var pattern : patterns) {
            if (pattern.equals(concrete)) {
                return true;
            }
            if ((pattern.contains("*") || pattern.contains("+"))
                    && TypeExpander.expand(pattern).contains(concrete)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] wordFromDecodeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return new byte[0];
        }
        var matcher = HEX_WORD_IN_COMMENT.matcher(comment);
        String best = null;
        while (matcher.find()) {
            var hex = matcher.group();
            if (best == null || hex.length() > best.length()) {
                best = hex;
            }
        }
        if (best == null) {
            return new byte[0];
        }
        var raw = HexUtil.fromHex(best.substring(2));
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length > 32) {
            return java.util.Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        }
        var padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }
}
