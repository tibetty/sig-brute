package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DecodeMainValidateTest {

    private static final PrintStream DEV_NULL = new PrintStream(new ByteArrayOutputStream(), false,
        StandardCharsets.UTF_8);

    @Test
    void validateFlag_emitsParsableYaml() throws Exception {
        var path = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata")
            .toAbsolutePath().toString();
        var out = new ByteArrayOutputStream();
        var code = DecodeMain.decode(new String[]{"--validate", path}, new PrintStream(out, true,
            StandardCharsets.UTF_8), DEV_NULL);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("selector:"));
    }
}
