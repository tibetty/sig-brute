package me.tibetty.sigbrute.decode.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import me.tibetty.sigbrute.decode.CorpusFixtureLocator;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.TupleCorpusTestSupport;
import me.tibetty.sigbrute.decode.TupleCorpusTestSupport.ShallowEvalMode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Targeted skeleton-only regressions for known headroom categories. Disabled tests document
 * fixtures that still fail skeleton-only decode; enable as fixes land.
 */
class SkeletonOnlyHeadroomTest {

    @Disabled("array_suffix — dynamic head fields still missing [] in some tuple bodies")
    @Test
    void harvest_skeletonOnly_nestedStructure() throws Exception {
        assertSkeletonOnly(
            "harvest((uint256,bool,uint256,uint8,address[],address[],bytes32[],bytes[],uint128,bytes32[]),(uint16,bytes,address,address,uint256,uint256)[])");
    }

    @Disabled("layout_only_gap — needs calldata-driven head layout without Function:")
    @Test
    void modifyLiquidity_skeletonOnly_nestedStructure() throws Exception {
        assertSkeletonOnly(
            "modifyLiquidity((address,address,uint24,int24,address),(int24,int24,int256,bytes32),bytes)");
    }

    @Disabled("multi_dim_array — exodus uint256[][] promotion incomplete")
    @Test
    void exodus_skeletonOnly_nestedStructure() throws Exception {
        assertSkeletonOnly(
            "exodus((uint256[][],uint256[],uint8[]),(uint256[],uint8[]),address)");
    }

    private static void assertSkeletonOnly(String knownSig) throws Exception {
        assumeTrue(CorpusFixtureLocator.localCorpusPresent());
        var file = CorpusFixtureLocator.pathForSignature(knownSig);
        var input = CalldataInput.parse(Files.readString(file, StandardCharsets.UTF_8));
        var hints = TupleCorpusTestSupport.inlineHints(input, ShallowEvalMode.SKELETON_ONLY);
        var decoded = TupleCorpusTestSupport.decodeGreedy(input.body(), hints);
        var known = SignatureStructure.parseSignature(knownSig);
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)),
            () -> file.getFileName() + " structure mismatch");
    }
}
