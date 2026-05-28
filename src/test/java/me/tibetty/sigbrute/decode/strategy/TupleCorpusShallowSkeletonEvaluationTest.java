package me.tibetty.sigbrute.decode.strategy;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.SignatureStructure;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Structural evaluation for {@code decode --shallow-skeleton}: opaque top-level hints plus
 * inline {@code Function:} types for layout and inner nesting (leaf types not compared).
 * Writes {@code structure-check-shallow.json} when the local corpus is present.
 */
class TupleCorpusShallowSkeletonEvaluationTest {

    private static final Path CORPUS = Path.of("scratch/tuple-calldata-corpus");

    @Test
    @SuppressWarnings("unchecked")
    void shallowSkeletonStructureMatchesKnownSignatures() throws Exception {
        var manifestPath = CORPUS.resolve("manifest.json");
        assumeTrue(Files.isRegularFile(manifestPath), "local corpus not present — run fetch script");

        var manifest = (List<Map<String, Object>>) new Yaml().load(
            Files.readString(manifestPath, StandardCharsets.UTF_8));
        var mismatches = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var greedyMatch = 0;
        var searchMatch = 0;

        for (var entry : manifest) {
            var file = CORPUS.resolve((String) entry.get("file"));
            var knownSig = (String) entry.get("text_signature");
            var label = file.getFileName().toString();
            try {
                var input = CalldataInput.parse(Files.readString(file, StandardCharsets.UTF_8));
                var shallowHint = input.withShallowSkeleton().topLevelTypes();
                List<String> layoutInline = input.topLevelTypes() != null
                    ? input.topLevelTypes()
                    : List.of();
                var known = SignatureStructure.parseSignature(knownSig);

                var greedy = AbiDecoder.decodeResult(input.body(), shallowHint,
                    DecodeStrategy.GREEDY, false, layoutInline).args();
                if (known.structureEquals(SignatureStructure.fromDecodedArgs(greedy))) {
                    greedyMatch++;
                } else {
                    mismatches.add(label + " [greedy] — known: " + knownSig);
                }

                var search = AbiDecoder.decodeResult(input.body(), shallowHint,
                    DecodeStrategy.HEURISTIC_SEARCH, false, layoutInline).args();
                if (known.structureEquals(SignatureStructure.fromDecodedArgs(search))) {
                    searchMatch++;
                } else {
                    mismatches.add(label + " [heuristic_search] — known: " + knownSig);
                }
            } catch (Exception e) {
                errors.add(label + ": " + e.getMessage());
            }
        }

        writeReport(greedyMatch, searchMatch, manifest.size(), mismatches, errors);
        if (!errors.isEmpty()) {
            System.err.println("Decode errors:\n" + String.join("\n", errors));
        }
    }

    private static void writeReport(
        int greedyMatch, int searchMatch, int total, List<String> mismatches, List<String> errors
    ) throws Exception {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total\": ").append(total).append(",\n");
        sb.append("  \"greedy_match\": ").append(greedyMatch).append(",\n");
        sb.append("  \"heuristic_search_match\": ").append(searchMatch).append(",\n");
        sb.append("  \"mismatch\": ").append(mismatches.size()).append(",\n");
        sb.append("  \"error\": ").append(errors.size()).append(",\n");
        sb.append("  \"mismatches\": ").append(jsonStringList(mismatches)).append(",\n");
        sb.append("  \"errors\": ").append(jsonStringList(errors)).append("\n");
        sb.append("}\n");
        Files.writeString(
            CORPUS.resolve("structure-check-shallow.json"),
            sb.toString(),
            StandardCharsets.UTF_8);
        System.out.printf(
            "Shallow skeleton structure check: greedy %d/%d, heuristic_search %d/%d,"
                + " %d mismatch, %d error%n",
            greedyMatch, total, searchMatch, total, mismatches.size(), errors.size());
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
