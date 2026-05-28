package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.junit.jupiter.api.Test;

class ShallowSkeletonDecodeTest {

    @Test
    void swapMulti_shallowSkeleton_matchesKnownStructure() throws Exception {
        var file = Path.of("scratch/tuple-calldata-corpus/calldata/283_fef828dc_swapMulti.calldata");
        assumeTrue(Files.isRegularFile(file), "local corpus fixture not present");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text).withShallowSkeleton();
        var decoded = AbiDecoder.decodeResult(input.body(), input.topLevelTypes(),
            DecodeStrategy.HEURISTIC_SEARCH, false, List.of()).args();
        var known = SignatureStructure.parseSignature(
            "swapMulti((address,uint256,address)[],(address,uint256,uint256,address)[],bytes,address,(uint64,uint64,address))");
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

    @Test
    void collectFee_shallowSkeleton_matchesKnownStructure() throws Exception {
        var file = Path.of("scratch/tuple-calldata-corpus/calldata/075_3ed0e7b2_collectFee.calldata");
        assumeTrue(Files.isRegularFile(file), "local corpus fixture not present");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text).withShallowSkeleton();
        var decoded = AbiDecoder.decodeResult(input.body(), input.topLevelTypes(),
            DecodeStrategy.GREEDY, false, List.of()).args();
        var known = SignatureStructure.parseSignature(
            "collectFee(address[],address[],(uint8,address)[][])");
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

    @Test
    @org.junit.jupiter.api.Disabled("Nested (uint256,bytes)[] inside a single opaque top-level tuple is not recovered yet")
    void send_shallowSkeleton_matchesKnownStructure() throws Exception {
        var file = Path.of("scratch/tuple-calldata-corpus/calldata/047_27ad57d5_send.calldata");
        assumeTrue(Files.isRegularFile(file), "local corpus fixture not present");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text).withShallowSkeleton();
        var decoded = AbiDecoder.decodeResult(input.body(), input.topLevelTypes(),
            DecodeStrategy.HEURISTIC_SEARCH, false, List.of()).args();
        var known = SignatureStructure.parseSignature(
            "send((bytes32,uint256,(uint256,bytes)[],bytes))");
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }
}
