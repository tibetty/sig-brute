package me.tibetty.sigbrute.decode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Resolves local corpus fixture paths from {@code scratch/tuple-calldata-corpus/manifest.json}.
 *
 * <p>Tests should avoid hard-coding numbered fixture filenames because corpus reindexing renames
 * files while preserving selector/signature.
 */
public final class CorpusFixtureLocator {

    private static final Path CORPUS = Path.of("scratch/tuple-calldata-corpus");
    private static final Path MANIFEST = CORPUS.resolve("manifest.json");

    private CorpusFixtureLocator() {
    }

    public static boolean localCorpusPresent() {
        return Files.isRegularFile(MANIFEST);
    }

    public static Path pathForSignature(String textSignature) throws IOException {
        @SuppressWarnings("unchecked")
        var manifest = (List<Map<String, Object>>) new Yaml().load(
            Files.readString(MANIFEST, StandardCharsets.UTF_8));
        for (var row : manifest) {
            if (textSignature.equals(row.get("text_signature"))) {
                return CORPUS.resolve((String) row.get("file"));
            }
        }
        throw new IllegalArgumentException("signature not found in local corpus manifest: " + textSignature);
    }
}
