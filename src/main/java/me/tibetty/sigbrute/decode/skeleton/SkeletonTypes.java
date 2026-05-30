package me.tibetty.sigbrute.decode.skeleton;

import java.util.List;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;

/**
 * Normalizes Etherscan / 4byte skeleton type strings. Supports opaque {@code tuple} and inline
 * {@code (T,...)} forms from full text signatures.
 */
public final class SkeletonTypes {

    static final String TUPLE = "tuple";

    private SkeletonTypes() {
    }

    public static SkeletonTypeKind kind(String type) {
        if (AbiTypeSyntax.isDynamicBytesOrString(type)) {
            return SkeletonTypeKind.DYNAMIC_SCALAR;
        }
        if (TUPLE.equals(type)) {
            return SkeletonTypeKind.OPAQUE_TUPLE;
        }
        if (isInlineTuple(type)) {
            return SkeletonTypeKind.STATIC;
        }
        var arrayParts = AbiTypeSyntax.splitOutermostArraySuffix(type);
        if (arrayParts != null && arrayParts.suffix().contains("[]")) {
            if (isInlineTuple(arrayParts.base())) {
                return SkeletonTypeKind.INLINE_TUPLE_ARRAY;
            }
            if (TUPLE.equals(arrayParts.base())) {
                return SkeletonTypeKind.OPAQUE_TUPLE_ARRAY;
            }
            return SkeletonTypeKind.DYNAMIC_PRIM_ARRAY;
        }
        if (AbiTypeSyntax.isDynamicHint(type)) {
            return SkeletonTypeKind.DYNAMIC_PRIM_ARRAY;
        }
        return SkeletonTypeKind.STATIC;
    }

    public static boolean isTupleHint(String type) {
        return TUPLE.equals(type) || isInlineTuple(type);
    }

    /** {@code (T,...)} with no trailing {@code […]} brackets. */
    public static boolean isInlineTuple(String type) {
        return type.startsWith("(") && type.endsWith(")") && !type.endsWith("[]");
    }

    /**
     * Base inline tuple inside array suffixes, e.g. {@code (uint8,address)[][]} →
     * {@code (uint8,address)}.
     */
    public static String inlineTupleBase(String type) {
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(type);
        var base = parts != null ? parts.base() : type;
        return isInlineTuple(base) ? base : null;
    }

    /** Field type strings inside an inline tuple, e.g. {@code (uint256,address)} → two types. */
    public static List<String> inlineFieldTypes(String inlineTuple) {
        if (!isInlineTuple(inlineTuple)) {
            throw new IllegalArgumentException("not an inline tuple: " + inlineTuple);
        }
        return CalldataInput.splitTopLevelTypes(inlineTuple.substring(1, inlineTuple.length() - 1));
    }
}
