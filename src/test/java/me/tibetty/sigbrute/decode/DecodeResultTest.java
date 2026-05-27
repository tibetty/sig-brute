package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.junit.jupiter.api.Test;

class DecodeResultTest {

    @Test
    void decodeResultIncludesWarningsFromContext() {
        var hex = "0xa9059cbb"
            + "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
            + "00000000000000000000000000000000000000000000000000000000000003e8";
        var input = CalldataInput.parse(hex);
        var result = AbiDecoder.decodeResult(input.body(), input.topLevelTypes(),
            DecodeStrategy.GREEDY);
        assertFalse(result.args().isEmpty());
        assertEquals(DecodeStrategy.GREEDY, result.strategy());
    }
}
