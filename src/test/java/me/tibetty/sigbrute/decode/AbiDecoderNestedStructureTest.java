package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.tibetty.sigbrute.decode.abi.AbiTestEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Structural decode tests for nested tuples and multi-dimensional dynamic arrays. Uses synthetic
 * {@link AbiTestEncoder} calldata plus local corpus fixtures when present.
 */
class AbiDecoderNestedStructureTest {

    @Test
    void multiDimDynamicInlineTupleArray_synthetic() {
        var row = AbiTestEncoder.inlineTupleStaticArray(
            AbiTestEncoder.uint(1),
            AbiTestEncoder.address("91773f5e7ad5a47460e56e56ee6eddf450b36c7c"));
        var matrix = AbiTestEncoder.offsetIndexedArray(row);
        var text = AbiTestEncoder.etherscanDump(
            "matrix((uint8,address)[][])",
            "matrix((uint8,address)[][])",
            matrix);
        assertStructureEquals(text, "matrix((uint8,address)[][])");
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
        var tuple = (DecodedArg.Tuple) decoded.get(0);
        assertEquals("[][]", tuple.arraySuffix());
        assertEquals(2, tuple.fields().size());
    }

    @Test
    void staticTupleWithNestedInlineTuple_synthetic() {
        var text = """
            Function: wrap((uint256,(address,uint8)))

            MethodID: 0x12345678
            [0]: 00000000000000000000000000000000000000000000000000000000000003e8
            [1]: 000000000000000000000000dac17f958d2ee523a2206206994597c13d831ec7
            [2]: 0000000000000000000000000000000000000000000000000000000000000007
            """;
        assertStructureEquals(text, "wrap((uint256,(address,uint8)))");
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
        var outerTuple = (DecodedArg.Tuple) decoded.get(0);
        assertEquals(2, outerTuple.fields().size());
        assertInstanceOf(DecodedArg.Tuple.class, outerTuple.fields().get(1));
    }

    @Test
    void primBytes32DoubleDynamicArray_synthetic() {
        var cell = AbiTestEncoder.bytesN(
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20", 32);
        var row = AbiTestEncoder.staticArray(cell);
        var matrix = AbiTestEncoder.offsetIndexedArray(row);
        var text = AbiTestEncoder.etherscanDump(
            "m(bytes32[][])",
            "m(bytes32[][])",
            matrix);
        assertStructureEquals(text, "m(bytes32[][])");
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
        assertInstanceOf(DecodedArg.PrimArray.class, decoded.get(0));
        assertEquals("[][]", ((DecodedArg.PrimArray) decoded.get(0)).arraySuffix());
    }

    @Test
    void tupleWithDynamicInlineTupleArrayField_synthetic() {
        var pairs = AbiTestEncoder.inlineTupleStaticArray(
            AbiTestEncoder.address("a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
            AbiTestEncoder.uint(42));
        var text = AbiTestEncoder.etherscanDump(
            "go((address,uint16)[])",
            "go((address,uint16)[])",
            pairs);
        assertStructureEquals(text, "go((address,uint16)[])");
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
        var tuple = (DecodedArg.Tuple) decoded.get(0);
        assertEquals("[]", tuple.arraySuffix());
        assertEquals(2, tuple.fields().size());
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void corpus_collectFee_inlineTupleDoubleArray() throws Exception {
        assertCorpusStructure(
            "scratch/tuple-calldata-corpus/calldata/166_3ed0e7b2_collectFee.calldata",
            "collectFee(address[],address[],(uint8,address)[][])");
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void corpus_executeHooks_tupleOfPrimArraysIncludingMatrix() throws Exception {
        assertCorpusStructure(
            "scratch/tuple-calldata-corpus/calldata/058_2f82b89a_executeHooks.calldata",
            "executeHooks((address[],bytes[],uint256[],bytes32[][],bytes32[][]))");
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void corpus_launchStroid_staticTupleWithInlineTupleArray() throws Exception {
        assertCorpusStructure(
            "scratch/tuple-calldata-corpus/calldata/057_f6dbcb18_launchStroidDotFun.calldata",
            "launchStroidDotFun(string,string,string,bytes32,((address,uint16)[],uint24,address,uint8))");
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void corpus_executeBundle_nestedTupleWithTupleArrayField() throws Exception {
        assertCorpusStructure(
            "scratch/tuple-calldata-corpus/calldata/036_95d7a060_executeBundle.calldata",
            "executeBundle(address,(uint256,(uint256,uint256,bool,bool,uint256,uint8,(string,(string,uint8)[],bytes4[],bytes[]))[]),bytes)");
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void corpus_updateOrdersConfiguration_nestedTupleArrays() throws Exception {
        assertCorpusStructure(
            "scratch/tuple-calldata-corpus/calldata/095_0edc3c64_updateOrdersConfiguration.calldata",
            "updateOrdersConfiguration(address[],"
                + "(uint256,uint256,uint256,((uint256,uint256,int256)[],"
                + "(uint256,uint256,int256)[]))[])");
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void corpus_claimRewards_tupleOfPrimArraysIncludingMatrix() throws Exception {
        assertCorpusStructure(
            "scratch/tuple-calldata-corpus/calldata/059_62b88f54_claimRewards.calldata",
            "claimRewards((address[],address[],uint256[],bytes32[][]))");
    }

    static boolean localCorpusPresent() {
        return Files.isRegularFile(Path.of("scratch/tuple-calldata-corpus/manifest.json"));
    }

    private static void assertCorpusStructure(String calldataPath, String signature) throws Exception {
        var text = Files.readString(Path.of(calldataPath), StandardCharsets.UTF_8);
        assertStructureMatchesSignature(text, signature);
    }

    private static void assertStructureEquals(String etherscanText, String signature) {
        assertStructureMatchesSignature(etherscanText, signature);
    }

    private static void assertStructureMatchesSignature(String etherscanText, String signature) {
        var input = CalldataInput.parse(etherscanText);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
        var known = SignatureStructure.parseSignature(signature);
        var got = SignatureStructure.fromDecodedArgs(decoded);
        assertTrue(known.structureEquals(got),
            () -> "structure mismatch for " + signature + "\nknown:\n"
                + formatNodes(known.topLevel()) + "\ngot:\n" + formatNodes(got.topLevel()));
    }

    private static String formatNodes(java.util.List<SignatureStructure.Node> nodes) {
        var sb = new StringBuilder();
        formatNodes(nodes, sb, 0);
        return sb.toString();
    }

    private static void formatNodes(java.util.List<SignatureStructure.Node> nodes, StringBuilder sb,
        int depth) {
        for (var n : nodes) {
            sb.append("  ".repeat(depth)).append(n.kind()).append(" suffix='")
                .append(n.arraySuffix()).append("' children=").append(n.children().size())
                .append('\n');
            formatNodes(n.children(), sb, depth + 1);
        }
    }

}
