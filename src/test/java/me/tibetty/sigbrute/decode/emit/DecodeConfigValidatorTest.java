package me.tibetty.sigbrute.decode.emit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.tibetty.sigbrute.decode.AbiDecoder;
import me.tibetty.sigbrute.decode.CalldataInput;
import me.tibetty.sigbrute.decode.DecodeResult;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;
import org.junit.jupiter.api.Test;

class DecodeConfigValidatorTest {

    @Test
    void validate_acceptsRoundTripYaml() {
        var hex = "0xa9059cbb"
            + "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
            + "00000000000000000000000000000000000000000000000000000000000003e8";
        var input = CalldataInput.parse(hex);
        DecodeResult result = AbiDecoder.decodeResult(input.body(), null, DecodeStrategy.GREEDY);
        var yaml = ConfigEmitter.emit(input.selector(), "transfer", result);
        assertDoesNotThrow(() -> DecodeConfigValidator.validate(input.selector(), yaml));
    }

    @Test
    void validate_rejectsMismatchedSelector() {
        var yaml = """
            selector: "0xdeadbeef"
            method_names: [foo]
            args:
              - [uint256]
            """;
        assertThrows(IllegalArgumentException.class,
            () -> DecodeConfigValidator.validate(new byte[]{1, 2, 3, 4}, yaml));
    }
}
