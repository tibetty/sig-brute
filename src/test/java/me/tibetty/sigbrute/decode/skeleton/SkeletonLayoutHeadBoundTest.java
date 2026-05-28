package me.tibetty.sigbrute.decode.skeleton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.CorpusFixtureLocator;
import me.tibetty.sigbrute.decode.ShallowSkeletonHints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Layout head-bound and slack absorption for shallow skeleton + inline {@code Function:} types. */
class SkeletonLayoutHeadBoundTest {

    @Test
    @EnabledIf("localCorpusPresent")
    void executeHooks_resolvesOneTopLevelTupleSlot() throws Exception {
        assertTopLevelTupleSlots(
            "executeHooks((address[],bytes[],uint256[],bytes32[][],bytes32[][]))", 0, 1);
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void deposit_shallowLayout_keepsOneSlotForDynamicOpaqueTuple() throws Exception {
        assertTopLevelTupleSlots(
            "deposit(((uint8,uint256,bytes32,uint256)[],uint8,uint256,bytes32,"
                + "(bytes32,uint256)[],uint256,uint256,(uint8,bytes32)[]),bytes,uint256)",
            0,
            1);
    }

    private static void assertTopLevelTupleSlots(String knownSig, int argIndex, int expectedSlots)
        throws Exception {
        var text = Files.readString(
            CorpusFixtureLocator.pathForSignature(knownSig),
            StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text);
        var shallow = input.withShallowSkeleton();
        var layoutHint = ShallowSkeletonHints.layoutHints(
            shallow.topLevelTypes(), input.topLevelTypes());
        var head = SkeletonLayout.HeadSection.of(input.body());
        var demand = SkeletonLayout.computeSlotDemand(layoutHint);
        var tupleSlots = SkeletonLayout.resolveTupleSlots(input.body(), layoutHint, demand, head);
        SkeletonLayout.absorbRemainingSlack(input.body(), layoutHint, demand.minSlots(), tupleSlots, head);
        assertEquals(expectedSlots, tupleSlots[argIndex]);
    }

    static boolean localCorpusPresent() {
        return CorpusFixtureLocator.localCorpusPresent();
    }
}
