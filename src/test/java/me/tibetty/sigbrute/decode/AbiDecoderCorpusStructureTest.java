package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Regression tests for local corpus samples that failed structural comparison. */
@EnabledIf("localCorpusPresent")
class AbiDecoderCorpusStructureTest {

    static boolean localCorpusPresent() {
        return Files.isRegularFile(Path.of("scratch/tuple-calldata-corpus/manifest.json"));
    }

    @Test
    void collectFee_inlineTupleDoubleArray() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/166_3ed0e7b2_collectFee.calldata",
            "collectFee(address[],address[],(uint8,address)[][])");
    }

    @Test
    void register_staticInlineTuple_matchesKnownShape() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/012_ef9c8805_register.calldata",
            "register((string,address,uint256,bytes32,address,bytes[],uint8,bytes32))");
    }

    @Test
    void transferWithWinternitz_fixedBytes32Array67() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/092_c103dd63_transferWithWinternitz.calldata",
            "transferWithWinternitz((bytes32,bytes32),(bytes32[67]),address,uint256)");
        var text = Files.readString(
            Path.of("scratch/tuple-calldata-corpus/calldata/092_c103dd63_transferWithWinternitz.calldata"),
            StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
        var inner = (DecodedArg.Tuple) decoded.get(1);
        assertInstanceOf(DecodedArg.PrimArray.class, inner.fields().get(0));
    }

    @Test
    void executeBundle_nestedTupleArray() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/036_95d7a060_executeBundle.calldata",
            "executeBundle(address,(uint256,(uint256,uint256,bool,bool,uint256,uint8,(string,(string,uint8)[],bytes4[],bytes[]))[]),bytes)");
    }

    @Test
    void transfer_inlineTupleArray() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/038_08af27de_transfer.calldata",
            "transfer(uint256,uint256,uint256,address,uint256,bytes,(address,uint256)[])");
    }

    @Test
    void executeHooks_tupleOfArrays() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/058_2f82b89a_executeHooks.calldata",
            "executeHooks((address[],bytes[],uint256[],bytes32[][],bytes32[][]))");
    }

    @Test
    void launchStroid_nestedTupleArray() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/057_f6dbcb18_launchStroidDotFun.calldata",
            "launchStroidDotFun(string,string,string,bytes32,((address,uint16)[],uint24,address,uint8))");
    }

    @Test
    void claimRewards_bytes32Matrix() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/059_62b88f54_claimRewards.calldata",
            "claimRewards((address[],address[],uint256[],bytes32[][]))");
    }

    @Test
    void fulfil_deposit_inlineTupleArrayInStaticTuple() throws Exception {
        var inner = "((uint8,uint256,bytes32,uint256)[],uint8,uint256,bytes32,"
            + "(bytes32,uint256)[],uint256,uint256,(uint8,bytes32)[])";
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/028_411e1fee_fulfil.calldata",
            "fulfil(" + inner + ",bytes)");
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/024_2b653696_deposit.calldata",
            "deposit(" + inner + ",bytes,uint256)");
    }

    @Test
    void updateOrdersConfiguration_nestedTupleArrays() throws Exception {
        assertStructureMatches(
            "scratch/tuple-calldata-corpus/calldata/095_0edc3c64_updateOrdersConfiguration.calldata",
            "updateOrdersConfiguration(address[],"
                + "(uint256,uint256,uint256,((uint256,uint256,int256)[],"
                + "(uint256,uint256,int256)[]))[])");
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

    private static void assertStructureMatches(String calldataPath, String knownSig) throws Exception {
        var text = Files.readString(Path.of(calldataPath), StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
        var known = SignatureStructure.parseSignature(knownSig);
        var got = SignatureStructure.fromDecodedArgs(decoded);
        assertTrue(known.structureEquals(got),
            () -> "structure mismatch for " + calldataPath + "\nknown: "
                + formatNodes(known.topLevel()) + "\ngot: " + formatNodes(got.topLevel()));
    }
}
