package me.tibetty.sigbrute.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.tibetty.sigbrute.model.LeafArgSpec;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.model.TupleArgSpec;
import org.junit.jupiter.api.Test;

class YamlConfigParserTest {

    private SearchConfig parse(String yaml) {
        return new YamlConfigParser()
            .parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parseCompleteConfigWithBareListArg() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - [address, uint256]
            parallelism: 2
            find_first: true
            """;

        var cfg = parse(yaml);

        assertArrayEquals(new byte[]{(byte) 0xa9, (byte) 0x05, (byte) 0x9c, (byte) 0xbb},
            cfg.selector());
        assertEquals(List.of("transfer"), cfg.methodNames());
        assertEquals(1, cfg.args().size());
        assertInstanceOf(LeafArgSpec.class, cfg.args().get(0));
        assertEquals(List.of("address", "uint256"), ((LeafArgSpec) cfg.args().get(0)).patterns());
        assertEquals(2, cfg.parallelism());
        assertTrue(cfg.findFirst());
    }

    @Test
    void parseMethodNamesAsList() {
        var yaml = """
            selector: a9059cbb
            method_names: [transfer, safeTransfer]
            args:
              - [address]
            """;

        var cfg = parse(yaml);

        assertEquals(List.of("transfer", "safeTransfer"), cfg.methodNames());
    }

    @Test
    void parseMissingSelector() {
        var yaml = """
            method_names: transfer
            args:
              - [address]
            """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parse(yaml));
        assertTrue(ex.getMessage().contains("Missing required field: 'selector'"));
    }

    @Test
    void parseSelectorNotFourBytes() {
        var yaml = """
            selector: a9059c
            method_names: transfer
            args:
              - [address]
            """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parse(yaml));
        assertTrue(ex.getMessage().contains("selector must be exactly 4 bytes"));
    }

    @Test
    void parseMissingArgs() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parse(yaml));
        assertTrue(ex.getMessage().contains("Missing 'args'"));
    }

    @Test
    void parseInvalidArgSpecKeys() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - unknown: foo
            """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parse(yaml));
        assertTrue(ex.getMessage().contains("'()'"));
    }

    @Test
    void parseUnrecognizedMappingKeyThrows() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - preset: addr
            """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parse(yaml));
        assertTrue(ex.getMessage().contains("'()'"));
        assertTrue(ex.getMessage().contains("'[]'"));
        assertTrue(ex.getMessage().contains("got keys:"));
    }

    @Test
    void parseBareListWithArraySuffixes() {
        // Items containing [] must use block form — YAML flow sequences treat [] as nested
        // sequences
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - - bytes*[]
                - uint*[]
                - address[]
              - - address[]
            """;

        var cfg = parse(yaml);

        assertEquals(List.of("bytes*[]", "uint*[]", "address[]"),
            ((LeafArgSpec) cfg.args().get(0)).patterns());
        assertEquals(List.of("address[]"), ((LeafArgSpec) cfg.args().get(1)).patterns());
    }

    @Test
    void parsePlainTupleExpandsToGroupedTypes() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - ():
                  - [address]
                  - [uint256]
            """;

        var cfg = parse(yaml);

        TupleArgSpec tuple = (TupleArgSpec) cfg.args().get(0);
        assertEquals("", tuple.arraySuffix());
        assertEquals(List.of("(address,uint256)"), tuple.expand().stream().toList());
    }

    @Test
    void parseTupleArrayParenKey() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - "()[]":
                  - [address]
                  - [uint256]
            """;

        var cfg = parse(yaml);

        TupleArgSpec tuple = (TupleArgSpec) cfg.args().get(0);
        assertEquals("[]", tuple.arraySuffix());
        assertEquals(List.of("(address,uint256)[]"), tuple.expand().stream().toList());
    }

    @Test
    void parseTupleFixedArrayParenKey() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - "()[3]":
                  - [address]
                  - [uint256]
            """;

        var cfg = parse(yaml);

        TupleArgSpec tuple = (TupleArgSpec) cfg.args().get(0);
        assertEquals("[3]", tuple.arraySuffix());
        assertEquals(List.of("(address,uint256)[3]"), tuple.expand().stream().toList());
    }

    @Test
    void parseTupleArrayKeyWithMultiCandidateFields() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - "()[]":
                  - [address, bytes20]
                  - [uint*, int*]
            """;

        var cfg = parse(yaml);

        TupleArgSpec tuple = (TupleArgSpec) cfg.args().get(0);
        assertEquals("[]", tuple.arraySuffix());
        assertEquals(2, tuple.fields().size());
        assertEquals(List.of("address", "bytes20"),
            ((LeafArgSpec) tuple.fields().get(0)).patterns());
        assertEquals(List.of("uint*", "int*"), ((LeafArgSpec) tuple.fields().get(1)).patterns());
    }

    @Test
    void parseDuplicateTupleKeysThrows() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - ():
                  - [address]
                "()[]":
                  - [uint256]
            """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parse(yaml));
        assertTrue(ex.getMessage().contains("multiple '()'"));
    }

    @Test
    void parsePrimitiveArraySuffixesEachBaseType() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - "[]":
                  - address
                  - bytes*
            """;

        var cfg = parse(yaml);

        assertInstanceOf(LeafArgSpec.class, cfg.args().get(0));
        assertEquals(List.of("address[]", "bytes*[]"),
            ((LeafArgSpec) cfg.args().get(0)).patterns());
    }

    @Test
    void parsePrimitiveFixedArrayKey() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - "[3]":
                  - address
                  - bytes32
            """;

        var cfg = parse(yaml);

        assertEquals(List.of("address[3]", "bytes32[3]"),
            ((LeafArgSpec) cfg.args().get(0)).patterns());
    }

    @Test
    void parsePrimitiveArrayKeyFlowSequenceItems() {
        // Items written as YAML flow sequences inside "[]": are flattened into individual base
        // types
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - "[]":
                  - [address, bytes20]
                  - uint*
            """;

        var cfg = parse(yaml);

        assertEquals(List.of("address[]", "bytes20[]", "uint*[]"),
            ((LeafArgSpec) cfg.args().get(0)).patterns());
    }

    @Test
    void parsePrimitiveArrayScalarValueThrows() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - "[]": address
            """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parse(yaml));
        assertTrue(ex.getMessage().contains("'[]' value must be a list"));
    }

    @Test
    void parseInvalidTupleParenSuffixThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> parseInvalidTupleParenSuffixConfig());
        assertTrue(ex.getMessage().contains("Invalid array suffix in tuple key"));
    }

    private void parseInvalidTupleParenSuffixConfig() {
        parse("""
            selector: a9059cbb
            method_names: transfer
            args:
              - "()[bad]":
                  - [address]
            """);
    }

    @Test
    void parseShardKeys_defaultsToSingleShard() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - [address]
            """;
        var cfg = parse(yaml);
        assertEquals(0, cfg.shardIndex());
        assertEquals(1, cfg.totalShards());
    }

    @Test
    void parseShardKeys_readsShardIndexAndTotalShards() {
        var yaml = """
            selector: a9059cbb
            method_names: transfer
            args:
              - [address]
            shard_index: 2
            total_shards: 5
            """;
        var cfg = parse(yaml);

        assertEquals(2, cfg.shardIndex());
        assertEquals(5, cfg.totalShards());
    }
}
