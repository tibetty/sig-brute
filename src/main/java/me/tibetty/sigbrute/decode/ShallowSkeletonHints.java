package me.tibetty.sigbrute.decode;

import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.skeleton.SkeletonTypes;

/**
 * Shallow skeleton: collapse top-level inline {@code (T,...)} to opaque {@code tuple} /
 * {@code tuple[]} for layout hint names, while optionally retaining the original {@code Function:}
 * types for head-slot planning and per-parameter decode.
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
     * Type string used when decoding argument {@code argIndex}'s payload under shallow skeleton.
     */
    public static String bodyDecodeType(List<String> shallow, List<String> inline, int argIndex) {
        var shallowType = shallow.get(argIndex);
        if (inline == null || inline.isEmpty() || argIndex >= inline.size()) {
            return shallowType;
        }
        var inlineType = inline.get(argIndex);
        if (inlineType == null) {
            return shallowType;
        }
        if (SkeletonTypes.isInlineTuple(inlineType)
            || SkeletonTypes.inlineTupleBase(inlineType) != null) {
            return inlineType;
        }
        return shallowType;
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
}
