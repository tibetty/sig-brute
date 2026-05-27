package me.tibetty.sigbrute.decode.emit;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import me.tibetty.sigbrute.parser.YamlConfigParser;

/** Validates generated decode YAML round-trips through the config parser. */
public final class DecodeConfigValidator {

    private DecodeConfigValidator() {
    }

    public static void validate(byte[] expectedSelector, String yaml) {
        var config = new YamlConfigParser()
            .parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        if (!Arrays.equals(expectedSelector, config.selector())) {
            throw new IllegalArgumentException(
                "generated YAML selector does not match input calldata selector");
        }
        if (config.args().isEmpty()) {
            throw new IllegalArgumentException("generated YAML has no args");
        }
    }
}
