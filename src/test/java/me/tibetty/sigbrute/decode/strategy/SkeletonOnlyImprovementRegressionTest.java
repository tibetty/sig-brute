package me.tibetty.sigbrute.decode.strategy;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import me.tibetty.sigbrute.decode.TupleCorpusTestSupport;
import me.tibetty.sigbrute.decode.TupleCorpusTestSupport.ShallowEvalMode;
import org.junit.jupiter.api.Test;

/**
 * Guards skeleton-only decode accuracy on the optional local tuple corpus. Skips when the corpus
 * is absent; fails the build if layout-only or skeleton-only nested structure regresses below the
 * recorded baselines.
 */
class SkeletonOnlyImprovementRegressionTest {

    private static final int LAYOUT_BASELINE = 256;
    private static final int SKELETON_BASELINE = 240;

    @Test
    void skeletonOnlyDoesNotRegressLayoutOrBaseline() throws Exception {
        assumeTrue(TupleCorpusTestSupport.corpusPresent());

        var manifest = TupleCorpusTestSupport.loadManifest();
        var layoutMatch = TupleCorpusTestSupport.countNestedStructureMatches(manifest,
            ShallowEvalMode.LAYOUT_ONLY);
        var skeletonMatch = TupleCorpusTestSupport.countNestedStructureMatches(manifest,
            ShallowEvalMode.SKELETON_ONLY);

        var total = manifest.size();
        if (layoutMatch < LAYOUT_BASELINE) {
            throw new AssertionError("layout only regressed: " + layoutMatch + "/" + total);
        }
        if (skeletonMatch < SKELETON_BASELINE) {
            throw new AssertionError("skeleton only regressed: " + skeletonMatch + "/" + total);
        }
        System.out.printf("skeleton-only improvement check: layout %d/%d, skeleton %d/%d%n",
            layoutMatch, total, skeletonMatch, total);
    }
}
