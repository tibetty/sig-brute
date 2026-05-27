package me.tibetty.sigbrute.decode.strategy.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.CorpusFixtureLocator;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class GreedyBodyDecoderNestedArrayTest {

    @Test
    void emptyBytes32MatrixRow_decodesAsDoubleArraySuffix() {
        var matrix = new byte[96];
        matrix[31] = 1;
        matrix[63] = 32;
        matrix[95] = 0;

        var decoded = GreedyBodyDecoder.tryDecodeArrayFromLengthPrefix(
            DecodeStrategy.newContext(DecodeStrategy.GREEDY),
            matrix,
            java.math.BigInteger.ONE);
        assertInstanceOf(DecodedArg.PrimArray.class, decoded);
        assertEquals("[][]", ((DecodedArg.PrimArray) decoded).arraySuffix());
    }

    @Test
    @EnabledIf("localCorpusPresent")
    void executeHooks_emptyAndFullBytes32Matrices() throws Exception {
        var knownSig = "executeHooks((address[],bytes[],uint256[],bytes32[][],bytes32[][]))";
        var text = Files.readString(
            CorpusFixtureLocator.pathForSignature(knownSig),
            StandardCharsets.UTF_8);
        var input = CalldataInput.parse(text).withoutSkeleton();
        var decoded = AbiDecoder.decodeArgs(input.body(), null, DecodeStrategy.GREEDY);
        var known = SignatureStructure.parseSignature(knownSig);
        assertTrue(known.structureEquals(SignatureStructure.fromDecodedArgs(decoded)),
            () -> "got: " + decoded);
        var tuple = (DecodedArg.Tuple) decoded.get(0);
        assertEquals(5, tuple.fields().size());
        assertInstanceOf(DecodedArg.PrimArray.class, tuple.fields().get(3));
        assertEquals("[][]", ((DecodedArg.PrimArray) tuple.fields().get(3)).arraySuffix());
        assertEquals("[][]", ((DecodedArg.PrimArray) tuple.fields().get(4)).arraySuffix());
    }

    static boolean localCorpusPresent() {
        return CorpusFixtureLocator.localCorpusPresent();
    }
}
