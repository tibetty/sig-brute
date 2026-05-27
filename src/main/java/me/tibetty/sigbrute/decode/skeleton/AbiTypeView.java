package me.tibetty.sigbrute.decode.skeleton;

import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;

/**
 * Parsed view of a Solidity type string for skeleton decode. Separates the element/base type from
 * trailing array dimensions (parenthesis-aware).
 */
public final class AbiTypeView {

    private final String raw;
    private final String base;
    private final String arraySuffix;

    private AbiTypeView(String raw, String base, String arraySuffix) {
        this.raw = raw;
        this.base = base;
        this.arraySuffix = arraySuffix;
    }

    public static AbiTypeView of(String type) {
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(type);
        if (parts == null) {
            return new AbiTypeView(type, type, "");
        }
        return new AbiTypeView(type, parts.base(), parts.suffix());
    }

    /** Peels one outer {@code […]} dimension; returns null when no array suffix remains. */
    public AbiTypeView peelOneDimension() {
        var parts = AbiTypeSyntax.peelOutermostArrayDimension(raw);
        if (parts == null) {
            return null;
        }
        return AbiTypeView.of(parts.base());
    }

    public String raw() {
        return raw;
    }

    public String base() {
        return base;
    }

    public String arraySuffix() {
        return arraySuffix;
    }

    public boolean hasDynamicArray() {
        return arraySuffix.contains("[]");
    }

    public boolean isInlineTupleBase() {
        return SkeletonTypes.isInlineTuple(base);
    }

    public int dynamicDimensionCount() {
        if (arraySuffix.isEmpty()) {
            return 0;
        }
        var count = 0;
        for (var i = 0; i < arraySuffix.length(); i++) {
            if (arraySuffix.charAt(i) == '[') {
                count++;
            }
        }
        return count;
    }
}
