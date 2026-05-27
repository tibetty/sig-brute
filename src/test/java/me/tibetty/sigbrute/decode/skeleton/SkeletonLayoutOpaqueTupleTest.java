package me.tibetty.sigbrute.decode.skeleton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

class SkeletonLayoutOpaqueTupleTest {

    @Test
    void countStaticHeadSlotsUntilOffset_stopsAtTailPointer() {
        var body = new byte[10 * 32];
        for (var i = 0; i < 8; i++) {
            body[i * 32 + 31] = (byte) (i + 1);
        }
        body[8 * 32 + 31] = 1;
        body[8 * 32 + 30] = 1;
        var head = SkeletonLayout.HeadSection.of(body);
        var span = SkeletonLayout.countStaticHeadSlotsUntilOffset(body, head, 0);
        assertTrue(span >= 8, "span should include static tuple slots before tail offset, got " + span);
    }

    @Test
    void executeWithPermit_shallowSkeleton_matchesStructure() throws Exception {
        var file = Path.of("scratch/tuple-calldata-corpus/calldata/002_02c52a55_executeWithPermit.calldata");
        Assumptions.assumeTrue(Files.isRegularFile(file));
        var text = Files.readString(file, StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text).withShallowSkeleton();
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes(), DecodeStrategy.GREEDY);
        var known = SignatureStructure.parseSignature(
            "executeWithPermit((address,address,address,uint256,uint256,address,uint256,uint256),bytes,(address,uint256,bytes))");
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

    @Test
    void aggregate_shallowSkeleton_tupleArray() throws Exception {
        var file = Path.of("scratch/tuple-calldata-corpus/calldata/034_1acaa198_aggregate.calldata");
        Assumptions.assumeTrue(Files.isRegularFile(file));
        var text = Files.readString(file, StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text).withShallowSkeleton();
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes(), DecodeStrategy.GREEDY);
        var known = SignatureStructure.parseSignature(
            "aggregate((address,uint256,bytes)[])");
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

}
