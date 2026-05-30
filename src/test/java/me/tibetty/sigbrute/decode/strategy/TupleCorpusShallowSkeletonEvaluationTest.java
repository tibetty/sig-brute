package me.tibetty.sigbrute.decode.strategy;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.TupleCorpusTestSupport;
import me.tibetty.sigbrute.decode.TupleCorpusTestSupport.ShallowEvalMode;
import org.junit.jupiter.api.Test;

/**
 * Structural evaluation for {@code decode --shallow-skeleton}.
 *
 * <p>Reports two decode modes in {@code structure-check-shallow.json}, each with nested and
 * top-level-only scores:
 *
 * <ul>
 *   <li>{@code with_function_inline} — layout and interior both use inline {@code Function:} types
 *   <li>{@code opaque_only} — CLI {@code --shallow-skeleton}: {@code Function:} for head layout
 *       only; tuple interiors from calldata heuristics (no inner field names from the signature)
 *   <li>{@code skeleton_only} — explorer-style opaque top-level hints only ({@code tuple},
 *       {@code tuple[]}, primitives; no inline {@code (T,...)} types) for layout and decode
 * </ul>
 *
 * <p>Nested comparison matches full tuple/array nesting. {@code top_level_only} compares only
 * top-level arg count, leaf vs tuple, and array suffixes — tuple interiors are ignored.
 */
class TupleCorpusShallowSkeletonEvaluationTest {

    @Test
    void shallowSkeletonStructureMatchesKnownSignatures() throws Exception {
        assumeTrue(TupleCorpusTestSupport.corpusPresent(), "local corpus not present — run fetch script");

        var manifest = TupleCorpusTestSupport.loadManifest();

        var withInline = evaluate(manifest, ShallowEvalMode.WITH_FUNCTION_INLINE);
        var opaqueOnly = evaluate(manifest, ShallowEvalMode.LAYOUT_ONLY);
        var skeletonOnly = evaluate(manifest, ShallowEvalMode.SKELETON_ONLY);

        writeReport(manifest.size(), withInline, opaqueOnly, skeletonOnly);
        printSummary(withInline, opaqueOnly, skeletonOnly, manifest.size());
    }

    private static ModeResult evaluate(List<Map<String, Object>> manifest, ShallowEvalMode mode) {
        var nestedMismatches = new ArrayList<String>();
        var topLevelMismatches = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var nestedGreedyMatch = 0;
        var nestedSearchMatch = 0;
        var topLevelGreedyMatch = 0;
        var topLevelSearchMatch = 0;

        for (var entry : manifest) {
            var file = TupleCorpusTestSupport.CORPUS_DIR.resolve((String) entry.get("file"));
            var knownSig = (String) entry.get("text_signature");
            var label = file.getFileName().toString();
            try {
                var input = CalldataInput.parse(Files.readString(file, StandardCharsets.UTF_8));
                var hints = TupleCorpusTestSupport.inlineHints(input, mode);
                var knownNested = SignatureStructure.parseSignature(knownSig);
                var knownTopLevel = SignatureStructure.topLevelShapeFromSignature(knownSig);

                var greedy = AbiDecoder.decodeResult(input.body(), hints.shallowTopLevel(),
                    DecodeStrategy.GREEDY, false, hints.layoutInline(), hints.interiorInline()).args();
                if (matchesNested(knownNested, greedy)) {
                    nestedGreedyMatch++;
                } else {
                    nestedMismatches.add(label + " [greedy] — known: " + knownSig);
                }
                if (matchesTopLevel(knownTopLevel, greedy)) {
                    topLevelGreedyMatch++;
                } else {
                    topLevelMismatches.add(label + " [greedy] — known: " + knownSig);
                }

                var search = AbiDecoder.decodeResult(input.body(), hints.shallowTopLevel(),
                    DecodeStrategy.HEURISTIC_SEARCH, false, hints.layoutInline(), hints.interiorInline()).args();
                if (matchesNested(knownNested, search)) {
                    nestedSearchMatch++;
                } else {
                    nestedMismatches.add(label + " [heuristic_search] — known: " + knownSig);
                }
                if (matchesTopLevel(knownTopLevel, search)) {
                    topLevelSearchMatch++;
                } else {
                    topLevelMismatches.add(label + " [heuristic_search] — known: " + knownSig);
                }
            } catch (Exception e) {
                errors.add(label + ": " + e.getMessage());
            }
        }

        return new ModeResult(
            nestedGreedyMatch, nestedSearchMatch, nestedMismatches,
            topLevelGreedyMatch, topLevelSearchMatch, topLevelMismatches,
            errors);
    }

    private static boolean matchesNested(SignatureStructure known, List<me.tibetty.sigbrute.decode.DecodedArg> decoded) {
        return known.structureEquals(SignatureStructure.fromDecodedArgs(decoded));
    }

    private static boolean matchesTopLevel(SignatureStructure knownTopLevel,
        List<me.tibetty.sigbrute.decode.DecodedArg> decoded) {
        return knownTopLevel.topLevelShapeEquals(
            SignatureStructure.topLevelShapeFromDecodedArgs(decoded));
    }

    private static void printSummary(ModeResult withInline, ModeResult opaqueOnly,
        ModeResult skeletonOnly, int total) {
        printModeSummary("Function inline", withInline, total);
        printModeSummary("layout only (CLI)", opaqueOnly, total);
        printModeSummary("skeleton only", skeletonOnly, total);
        if (!withInline.errors().isEmpty() || !opaqueOnly.errors().isEmpty()
            || !skeletonOnly.errors().isEmpty()) {
            var all = new ArrayList<String>();
            all.addAll(withInline.errors());
            all.addAll(opaqueOnly.errors());
            all.addAll(skeletonOnly.errors());
            System.err.println("Decode errors:\n" + String.join("\n", all));
        }
    }

    private static void printModeSummary(String label, ModeResult result, int total) {
        System.out.printf(
            "Shallow skeleton (%s) nested:       greedy %d/%d, heuristic_search %d/%d%n",
            label, result.nestedGreedyMatch(), total, result.nestedSearchMatch(), total);
        System.out.printf(
            "Shallow skeleton (%s) top-level only: greedy %d/%d, heuristic_search %d/%d%n",
            label, result.topLevelGreedyMatch(), total, result.topLevelSearchMatch(), total);
    }

    private static void writeReport(int total, ModeResult withInline, ModeResult opaqueOnly,
        ModeResult skeletonOnly) throws Exception {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total\": ").append(total).append(",\n");
        appendMode(sb, "with_function_inline", withInline);
        sb.append(",\n");
        appendMode(sb, "opaque_only", opaqueOnly);
        sb.append(",\n");
        appendMode(sb, "skeleton_only", skeletonOnly);
        sb.append("\n}\n");
        Files.writeString(
            TupleCorpusTestSupport.CORPUS_DIR.resolve("structure-check-shallow.json"),
            sb.toString(),
            StandardCharsets.UTF_8);
    }

    private static void appendMode(StringBuilder sb, String key, ModeResult result) {
        sb.append("  \"").append(key).append("\": {\n");
        appendScoreBlock(sb, "nested", result.nestedGreedyMatch(), result.nestedSearchMatch(),
            result.nestedMismatches(), result.errors());
        sb.append(",\n");
        appendScoreBlock(sb, "top_level_only", result.topLevelGreedyMatch(),
            result.topLevelSearchMatch(), result.topLevelMismatches(), result.errors());
        sb.append("\n  }");
    }

    private static void appendScoreBlock(
        StringBuilder sb, String key, int greedyMatch, int searchMatch,
        List<String> mismatches, List<String> errors
    ) {
        sb.append("    \"").append(key).append("\": {\n");
        sb.append("      \"greedy_match\": ").append(greedyMatch).append(",\n");
        sb.append("      \"heuristic_search_match\": ").append(searchMatch).append(",\n");
        sb.append("      \"mismatch\": ").append(mismatches.size()).append(",\n");
        sb.append("      \"error\": ").append(errors.size()).append(",\n");
        sb.append("      \"mismatches\": ").append(jsonStringList(mismatches)).append(",\n");
        sb.append("      \"errors\": ").append(jsonStringList(errors)).append("\n");
        sb.append("    }");
    }

    private record ModeResult(
        int nestedGreedyMatch,
        int nestedSearchMatch,
        List<String> nestedMismatches,
        int topLevelGreedyMatch,
        int topLevelSearchMatch,
        List<String> topLevelMismatches,
        List<String> errors
    ) {
    }

    private static String jsonStringList(List<String> items) {
        var sb = new StringBuilder("[");
        for (var i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(items.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
