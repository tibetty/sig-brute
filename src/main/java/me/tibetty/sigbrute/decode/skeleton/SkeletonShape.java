package me.tibetty.sigbrute.decode.skeleton;

import me.tibetty.sigbrute.decode.abi.AbiTypeSyntax;
import me.tibetty.sigbrute.decode.abi.ArraySuffixComposer;
import me.tibetty.sigbrute.decode.DecodedArg;
import java.util.List;

/** Structural {@link DecodedArg} shapes derived from skeleton type hints (no tail bytes). */
public final class SkeletonShape {

    private SkeletonShape() {
    }

    public static DecodedArg shapeFromHint(String hint) {
        var inlineBase = SkeletonTypes.inlineTupleBase(hint);
        if (inlineBase != null) {
            var parts = AbiTypeSyntax.splitOutermostArraySuffix(hint);
            var suffix = parts != null ? parts.suffix() : "";
            return new DecodedArg.Tuple(suffix, shapeFieldsFromHints(SkeletonTypes.inlineFieldTypes(inlineBase)),
                suffix.isEmpty() ? "inline tuple (skeleton shape)" : "inline tuple[] (skeleton shape)");
        }
        var parts = AbiTypeSyntax.splitOutermostArraySuffix(hint);
        if (parts != null && parts.suffix().contains("[")) {
            return new DecodedArg.PrimArray(parts.suffix(), List.of(parts.base()),
                "array (skeleton shape)");
        }
        return new DecodedArg.Leaf(List.of(hint), "skeleton shape");
    }

    static List<DecodedArg> shapeFieldsFromHints(List<String> fieldHints) {
        return fieldHints.stream().map(SkeletonShape::shapeFromHint).toList();
    }

    static DecodedArg skeletonArrayFallback(String arrayType) {
        if (arrayType.indexOf('[') < 0) {
            return null;
        }
        return shapeFromHint(arrayType);
    }

    static DecodedArg wrapArraySuffix(DecodedArg decoded, String suffix) {
        return ArraySuffixComposer.attach(decoded, suffix);
    }

    static DecodedArg inlineTupleArrayElement(List<String> elementFieldHints,
        List<DecodedArg> decodedFields, String comment) {
        if (decodedFields.size() == elementFieldHints.size()) {
            return new DecodedArg.Tuple("", decodedFields, comment);
        }
        return new DecodedArg.Tuple("", shapeFieldsFromHints(elementFieldHints),
            comment + " — skeleton shape (element decode ambiguous)");
    }
}
