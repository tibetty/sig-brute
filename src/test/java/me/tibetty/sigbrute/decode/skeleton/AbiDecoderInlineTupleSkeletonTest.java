package me.tibetty.sigbrute.decode.skeleton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.CorpusFixtureLocator;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.SignatureStructure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Skeleton decode when the Function header uses inline {@code (T,...)} types from 4byte. */
@EnabledIf("localCorpusPresent")
class AbiDecoderInlineTupleSkeletonTest {

    static boolean localCorpusPresent() {
        return CorpusFixtureLocator.localCorpusPresent();
    }

    @Test
    void forwardEth_bytesAndStaticTuple() throws Exception {
        var knownSig = "forwardEth(bytes,(uint256,address))";
        var text = Files.readString(
            CorpusFixtureLocator.pathForSignature(knownSig),
            StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());

        assertEquals(2, decoded.size());
        assertInstanceOf(DecodedArg.Leaf.class, decoded.get(0));
        assertInstanceOf(DecodedArg.Tuple.class, decoded.get(1));
        var tup = (DecodedArg.Tuple) decoded.get(1);
        assertEquals("", tup.arraySuffix());
        assertEquals(2, tup.fields().size());
        assertTrue(SignatureStructure.parseSignature(knownSig)
            .structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }

    @Test
    void send_singleDynamicTupleArg() throws Exception {
        var text = Files.readString(
            Path.of("scratch/tuple-calldata-corpus/calldata/047_27ad57d5_send.calldata"),
            StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text);
        var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());

        assertEquals(1, decoded.size());
        assertInstanceOf(DecodedArg.Tuple.class, decoded.get(0));
        var outer = (DecodedArg.Tuple) decoded.get(0);
        assertEquals(4, outer.fields().size());
        assertTrue(SignatureStructure.parseSignature(
            "send((bytes32,uint256,(uint256,bytes)[],bytes))")
            .structureEquals(SignatureStructure.fromDecodedArgs(decoded)));
    }
}
