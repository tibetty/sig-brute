package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Regression tests for real on-chain tuple calldata samples (subset exported from the local
 * evaluation corpus). Each case decodes from calldata bytes only ({@link CalldataInput#withoutSkeleton()}
 * — the {@code Function:} line in fixtures is not passed to the decoder). Ground-truth shape comes
 * from {@code text_signature} in the manifest (fetched from 4byte at corpus build time only).
 * Both {@link DecodeStrategy#GREEDY} and {@link DecodeStrategy#HEURISTIC_SEARCH} must match.
 */
class TupleCalldataCorpusTest {

    @ParameterizedTest(name = "{0} [{3}]")
    @MethodSource("corpusCases")
    void decodedStructureMatchesKnownSignature(
        String id, String textSignature, String calldataResource, DecodeStrategy strategy
    ) throws Exception {
        var text = readResource(calldataResource);
        var input = CalldataInput.parse(text).withoutSkeleton();
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes(), strategy);
        var known = SignatureStructure.parseSignature(textSignature);
        var got = SignatureStructure.fromDecodedArgs(decoded);
        assertTrue(known.structureEquals(got),
            () -> id + " [" + strategy.id() + "]: structure mismatch for " + textSignature);
    }

    @SuppressWarnings("unchecked")
    private static Stream<Arguments> corpusCases() throws Exception {
        var manifestText = readResource("/tuple-corpus/manifest.json");
        var manifest = (List<Map<String, Object>>) new Yaml().load(manifestText);
        return manifest.stream().flatMap(row -> Stream.of(
            Arguments.of(
                row.get("id"),
                row.get("text_signature"),
                "/tuple-corpus/" + row.get("file"),
                DecodeStrategy.GREEDY),
            Arguments.of(
                row.get("id"),
                row.get("text_signature"),
                "/tuple-corpus/" + row.get("file"),
                DecodeStrategy.HEURISTIC_SEARCH)));
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = TupleCalldataCorpusTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
