package me.tibetty.sigbrute.decode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.yaml.snakeyaml.Yaml;

/**
 * Shared helpers for optional local tuple calldata corpus tests under
 * {@code scratch/tuple-calldata-corpus/} (gitignored). Tests call
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} when the corpus is absent.
 */
public final class TupleCorpusTestSupport {

    public static final Path CORPUS_DIR = Path.of("scratch/tuple-calldata-corpus");
    public static final Path MANIFEST = CORPUS_DIR.resolve("manifest.json");

    /**
     * How inline {@code Function:} types participate in shallow-skeleton evaluation.
     */
    public enum ShallowEvalMode {
        /** Layout and interior both use inline {@code Function:} types. */
        WITH_FUNCTION_INLINE,
        /** CLI {@code --shallow-skeleton}: layout from {@code Function:}, empty interior. */
        LAYOUT_ONLY,
        /** Explorer-style opaque top-level types only (no full signature). */
        SKELETON_ONLY
    }

    public record InlineHints(
        List<String> shallowTopLevel,
        List<String> layoutInline,
        List<String> interiorInline
    ) {
    }

    private TupleCorpusTestSupport() {
    }

    public static boolean corpusPresent() {
        return Files.isRegularFile(MANIFEST);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> loadManifest() throws IOException {
        return (List<Map<String, Object>>) new Yaml().load(
            Files.readString(MANIFEST, StandardCharsets.UTF_8));
    }

    public static InlineHints inlineHints(CalldataInput input, ShallowEvalMode mode) {
        var shallow = input.withShallowSkeleton().topLevelTypes();
        List<String> fullInline = input.topLevelTypes() != null ? input.topLevelTypes() : List.of();
        return switch (mode) {
            case WITH_FUNCTION_INLINE -> new InlineHints(shallow, fullInline, fullInline);
            case LAYOUT_ONLY -> new InlineHints(shallow, fullInline, List.of());
            case SKELETON_ONLY -> new InlineHints(shallow, shallow, List.of());
        };
    }

    public static List<DecodedArg> decodeGreedy(byte[] body, InlineHints hints) {
        return AbiDecoder.decodeResult(body, hints.shallowTopLevel(), DecodeStrategy.GREEDY, false,
            hints.layoutInline(), hints.interiorInline()).args();
    }

    public static int countNestedStructureMatches(List<Map<String, Object>> manifest, ShallowEvalMode mode)
        throws IOException {
        var matches = 0;
        for (var entry : manifest) {
            var file = CORPUS_DIR.resolve((String) entry.get("file"));
            var knownSig = (String) entry.get("text_signature");
            var input = CalldataInput.parse(Files.readString(file, StandardCharsets.UTF_8));
            var hints = inlineHints(input, mode);
            var known = SignatureStructure.parseSignature(knownSig);
            var decoded = decodeGreedy(input.body(), hints);
            if (known.structureEquals(SignatureStructure.fromDecodedArgs(decoded))) {
                matches++;
            }
        }
        return matches;
    }
}
