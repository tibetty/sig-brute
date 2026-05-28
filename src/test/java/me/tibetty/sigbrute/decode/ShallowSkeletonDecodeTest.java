package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Targeted shallow-skeleton regressions (corpus fixtures and inline calldata). Full coverage:
 * {@link me.tibetty.sigbrute.decode.strategy.TupleCorpusShallowSkeletonEvaluationTest}.
 */
class ShallowSkeletonDecodeTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusRegressionCases")
    @EnabledIf("localCorpusPresent")
    void corpusRegression_shallowSkeleton_matchesKnownStructure(String knownSig,
        DecodeStrategy strategy) throws Exception {
        assertShallowStructure(knownSig, strategy);
    }

    static Stream<Arguments> corpusRegressionCases() {
        return Stream.of(
            Arguments.of(
                "swapMulti((address,uint256,address)[],(address,uint256,uint256,address)[],"
                    + "bytes,address,(uint64,uint64,address))",
                DecodeStrategy.HEURISTIC_SEARCH),
            Arguments.of("collectFee(address[],address[],(uint8,address)[][])", DecodeStrategy.GREEDY),
            Arguments.of(
                "harvest((uint256,bool,uint256,uint8,address[],address[],bytes32[],bytes[],"
                    + "uint128,bytes32[]),(uint16,bytes,address,address,uint256,uint256)[])",
                DecodeStrategy.GREEDY),
            Arguments.of(
                "deposit(((uint8,uint256,bytes32,uint256)[],uint8,uint256,bytes32,"
                    + "(bytes32,uint256)[],uint256,uint256,(uint8,bytes32)[]),bytes,uint256)",
                DecodeStrategy.GREEDY),
            Arguments.of(
                "fulfil(((uint8,uint256,bytes32,uint256)[],uint8,uint256,bytes32,"
                    + "(bytes32,uint256)[],uint256,uint256,(uint8,bytes32)[]),bytes)",
                DecodeStrategy.GREEDY),
            Arguments.of(
                "atInversebrah(bytes28,(int56),int200,(bytes23),(int160,bytes29),uint208,uint240,"
                    + "int168,(bytes18,bytes30[]))",
                DecodeStrategy.GREEDY),
            Arguments.of(
                "multisendTokenPermit2(address,(address,uint256)[],uint256,address,bytes,"
                    + "((address,uint160,uint48,uint48),address,uint256))",
                DecodeStrategy.GREEDY),
            Arguments.of("executeWithWinternitz((bytes32,bytes32),(bytes32[67]),address,bytes)",
                DecodeStrategy.GREEDY),
            Arguments.of("transferWithWinternitz((bytes32,bytes32),(bytes32[67]),address,uint256)",
                DecodeStrategy.GREEDY),
            Arguments.of("executeHooks((address[],bytes[],uint256[],bytes32[][],bytes32[][]))",
                DecodeStrategy.GREEDY));
    }

    /**
     * Two consecutive all-static tuples: layout must use original inline types to know where the
     * first tuple ends and the second begins.
     */
    @Test
    void consecutiveStaticTuples_shallowSkeleton_correctFieldCounts() {
        var text = """
            Function: collectFees(uint256,(address,address,bytes32),(int32,int32))

            MethodID: 0x57b94f41
            [0]:  0000000000000000000000000000000047edcb16d3fcb2ed7e290e70a3c22f29
            [1]:  0000000000000000000000000000000000000000000000000000000000000000
            [2]:  000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48
            [3]:  0000000000000000000000000000000000000000000d1b71758e21960000137e
            [4]:  fffffffffffffffffffffffffffffffffffffffffffffffffffffffffed30362
            [5]:  fffffffffffffffffffffffffffffffffffffffffffffffffffffffffed62290
            """;
        var input = CalldataInput.parse(text);
        var shallow = input.withShallowSkeleton();
        var decoded = AbiDecoder.decodeResult(shallow.body(), shallow.topLevelTypes(),
            DecodeStrategy.GREEDY, false, input.topLevelTypes()).args();
        var known = SignatureStructure.parseSignature(
            "collectFees(uint256,(address,address,bytes32),(int32,int32))");
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

    @Test
    @org.junit.jupiter.api.Disabled("Nested (uint256,bytes)[] inside a single opaque top-level tuple is not recovered yet")
    @EnabledIf("localCorpusPresent")
    void send_shallowSkeleton_matchesKnownStructure() throws Exception {
        var knownSig = "send((bytes32,uint256,(uint256,bytes)[],bytes))";
        var file = Path.of("scratch/tuple-calldata-corpus/calldata/047_27ad57d5_send.calldata");
        assumeTrue(Files.isRegularFile(file));
        var text = Files.readString(file, StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text);
        var shallow = input.withShallowSkeleton();
        var decoded = AbiDecoder.decodeResult(shallow.body(), shallow.topLevelTypes(),
            DecodeStrategy.HEURISTIC_SEARCH, false, input.topLevelTypes()).args();
        assertTrue(SignatureStructure.parseSignature(knownSig)
            .structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

    private static void assertShallowStructure(String knownSig, DecodeStrategy strategy)
        throws Exception {
        var text = Files.readString(
            CorpusFixtureLocator.pathForSignature(knownSig),
            StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text);
        var shallow = input.withShallowSkeleton();
        var decoded = AbiDecoder.decodeResult(shallow.body(), shallow.topLevelTypes(),
            strategy, false, input.topLevelTypes()).args();
        assertTrue(SignatureStructure.parseSignature(knownSig)
            .structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

    static boolean localCorpusPresent() {
        return CorpusFixtureLocator.localCorpusPresent();
    }
}
