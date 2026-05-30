package me.tibetty.sigbrute.decode.strategy;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.TupleCorpusTestSupport;
import me.tibetty.sigbrute.decode.TupleCorpusTestSupport.ShallowEvalMode;
import org.junit.jupiter.api.Test;

/**
 * Maintainer diagnostic: classifies skeleton-only corpus mismatches and prints a summary to stdout.
 * Never fails the build — use {@link SkeletonOnlyImprovementRegressionTest} for regressions.
 *
 * <p>Categories map to skeleton-only work areas: {@code layout_only_gap} (static tuple head layout),
 * {@code tuple_array_element} / {@code array_suffix} (opaque {@code tuple[]} and inner arrays),
 * {@code multi_dim_array}, {@code fixed_array}, etc.
 */
class SkeletonOnlyFailureAnalysisTest {

    @Test
    void classifySkeletonOnlyFailures() throws Exception {
        assumeTrue(TupleCorpusTestSupport.corpusPresent());

        var manifest = TupleCorpusTestSupport.loadManifest();
        var categories = new LinkedHashMap<String, List<String>>();
        var layoutOnlyFixes = new ArrayList<String>();
        var sharedFailures = new ArrayList<String>();

        for (var entry : manifest) {
            var file = TupleCorpusTestSupport.CORPUS_DIR.resolve((String) entry.get("file"));
            var knownSig = (String) entry.get("text_signature");
            var label = file.getFileName().toString();
            var input = CalldataInput.parse(Files.readString(file, StandardCharsets.UTF_8));
            var skeletonHints = TupleCorpusTestSupport.inlineHints(input, ShallowEvalMode.SKELETON_ONLY);
            var layoutHints = TupleCorpusTestSupport.inlineHints(input, ShallowEvalMode.LAYOUT_ONLY);
            var known = SignatureStructure.parseSignature(knownSig);

            var skeleton = TupleCorpusTestSupport.decodeGreedy(input.body(), skeletonHints);
            if (known.structureEquals(SignatureStructure.fromDecodedArgs(skeleton))) {
                continue;
            }

            var layoutOnly = TupleCorpusTestSupport.decodeGreedy(input.body(), layoutHints);
            var layoutPasses = known.structureEquals(SignatureStructure.fromDecodedArgs(layoutOnly));

            var diff = firstStructuralDiff(known.topLevel(), SignatureStructure.fromDecodedArgs(skeleton).topLevel(),
                "$");
            var category = categorize(diff, knownSig, layoutPasses);
            categories.computeIfAbsent(category, k -> new ArrayList<>()).add(label + " :: " + diff + " :: " + knownSig);

            if (layoutPasses) {
                layoutOnlyFixes.add(label);
            } else {
                sharedFailures.add(label);
            }
        }

        System.out.println("=== skeleton_only failure analysis (" + manifest.size() + " fixtures) ===");
        System.out.println("layout-only would fix: " + layoutOnlyFixes.size());
        System.out.println("shared with opaque_only: " + sharedFailures.size());
        for (var e : categories.entrySet()) {
            System.out.printf("%n[%s] %d%n", e.getKey(), e.getValue().size());
            for (var line : e.getValue()) {
                System.out.println("  " + line);
            }
        }
    }

    private static String categorize(String diff, String knownSig, boolean layoutPasses) {
        if (layoutPasses) {
            return "layout_only_gap";
        }
        if (diff.contains("arraySuffix") || knownSig.contains("[][]") || knownSig.contains("[]")) {
            if (knownSig.contains("[][]")) {
                return "multi_dim_array";
            }
            if (diff.contains("TUPLE") && knownSig.contains("[]")) {
                return "tuple_array_element";
            }
            return "array_suffix";
        }
        if (knownSig.contains("[") && !knownSig.contains("[][]")) {
            var fixed = java.util.regex.Pattern.compile("\\[[0-9]+\\]");
            if (fixed.matcher(knownSig).find()) {
                return "fixed_array";
            }
        }
        if (diff.contains("child count")) {
            return "field_count";
        }
        if (diff.contains("kind")) {
            return "leaf_vs_tuple";
        }
        return "other";
    }

    private static String firstStructuralDiff(List<SignatureStructure.Node> known,
        List<SignatureStructure.Node> decoded, String path) {
        if (known.size() != decoded.size()) {
            return path + " top-level count " + decoded.size() + " != " + known.size();
        }
        for (var i = 0; i < known.size(); i++) {
            var k = known.get(i);
            var d = decoded.get(i);
            var p = path + "[" + i + "]";
            if (k.kind() != d.kind()) {
                return p + " kind " + d.kind() + " != " + k.kind();
            }
            if (!k.arraySuffix().equals(d.arraySuffix())) {
                return p + " arraySuffix '" + d.arraySuffix() + "' != '" + k.arraySuffix() + "'";
            }
            if (k.kind() == SignatureStructure.Node.Kind.TUPLE) {
                if (k.children().size() != d.children().size()) {
                    return p + " child count " + d.children().size() + " != " + k.children().size();
                }
                var inner = firstStructuralDiff(k.children(), d.children(), p);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return "unknown diff";
    }
}
