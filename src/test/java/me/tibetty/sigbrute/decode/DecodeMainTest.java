package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecodeMainTest {

    private static final PrintStream DEV_NULL = new PrintStream(new ByteArrayOutputStream(), false,
        StandardCharsets.UTF_8);

    private static ByteArrayOutputStream captureOut() {
        return new ByteArrayOutputStream();
    }

    private static ByteArrayOutputStream captureErr() {
        return new ByteArrayOutputStream();
    }

    private static PrintStream ps(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static String str(ByteArrayOutputStream buf) {
        return buf.toString(StandardCharsets.UTF_8);
    }

    // ── success paths ─────────────────────────────────────────────────────────

    @Test
    void shallowSkeleton_abstractsTopLevelHint_keepsInlineForBodyDecode(@TempDir Path tempDir)
        throws IOException {
        var calldata = """
            Function: forwardEth(bytes,(uint256,address))

            MethodID: 0xfcaabe3b
            [0]: 0000000000000000000000000000000000000000000000000000000000000060
            [1]: 000000000000000000000000000000000000000000000000000016bcc41e9000
            [2]: 00000000000000000000000082d9a407f99a95db4671e7021d625cbd0787a407
            """;
        var path = tempDir.resolve("inline_tuple.calldata");
        Files.writeString(path, calldata, StandardCharsets.UTF_8);
        var input = CalldataInput.parse(Files.readString(path, StandardCharsets.UTF_8));
        var shallow = input.withShallowSkeleton();
        assertEquals(java.util.List.of("bytes", "tuple"), shallow.topLevelTypes());
        assertNull(shallow.inlineTopLevelTypes());
        assertEquals(0,
            DecodeMain.decode(new String[]{"--shallow-skeleton", path.toString()}, ps(captureOut()),
                DEV_NULL));
    }

    @Test
    void ignoreAndShallowSkeleton_mutuallyExclusive() throws IOException {
        var path = "src/main/resources/examples/calldata/dag_swap_by_order_id.calldata";
        ByteArrayOutputStream err = captureErr();
        var code = DecodeMain.decode(
            new String[]{"--ignore-skeleton", "--shallow-skeleton", path},
            DEV_NULL,
            ps(err));
        assertEquals(1, code);
        assertTrue(str(err).contains("mutually exclusive"));
    }

    @Test
    void ignoreSkeleton_skipsFunctionHeaderHint() throws IOException {
        var path = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata")
            .toAbsolutePath().toString();
        ByteArrayOutputStream withSkeleton = captureOut();
        ByteArrayOutputStream withoutSkeleton = captureOut();
        assertEquals(0, DecodeMain.decode(new String[]{path}, ps(withSkeleton), DEV_NULL));
        assertEquals(0,
            DecodeMain.decode(new String[]{"--ignore-skeleton", path}, ps(withoutSkeleton),
                DEV_NULL));
        assertNotEquals(str(withSkeleton), str(withoutSkeleton));
    }

    @Test
    void decodeFromFile_writesYamlToOut() throws IOException {
        var path = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata")
            .toAbsolutePath().toString();
        ByteArrayOutputStream out = captureOut();
        var code = DecodeMain.decode(new String[]{path}, ps(out), DEV_NULL);
        assertEquals(0, code);
        var output = str(out);
        assertTrue(output.contains("selector:"));
        assertTrue(output.contains("method_names:"));
    }

    @Test
    void decodeFromStdin_writesYamlToOut() throws IOException {
        var erc20Input = """
            Function: transfer(address to, uint256 amount)

            MethodID: 0xa9059cbb
            [0]: 000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48
            [1]: 00000000000000000000000000000000000000000000000000000000000003e8
            """;

        InputStream savedIn = System.in;
        System.setIn(new ByteArrayInputStream(erc20Input.getBytes(StandardCharsets.UTF_8)));
        try {
            ByteArrayOutputStream out = captureOut();
            var code = DecodeMain.decode(new String[0], ps(out), DEV_NULL);
            assertEquals(0, code);
            var output = str(out);
            assertTrue(output.contains("selector:"));
            assertTrue(output.contains("method_names:"));
        } finally {
            System.setIn(savedIn);
        }
    }

    // ── error paths ───────────────────────────────────────────────────────────

    @Test
    void blankInput_returnsCode2() throws IOException {
        InputStream savedIn = System.in;
        System.setIn(new ByteArrayInputStream("   ".getBytes(StandardCharsets.UTF_8)));
        try {
            ByteArrayOutputStream err = captureErr();
            var code = DecodeMain.decode(new String[0], DEV_NULL, ps(err));
            assertEquals(2, code);
            assertTrue(str(err).contains("decode: input is empty"));
        } finally {
            System.setIn(savedIn);
        }
    }

    @Test
    void malformedInput_returnsCode1() throws IOException {
        InputStream savedIn = System.in;
        System.setIn(new ByteArrayInputStream("invalid".getBytes(StandardCharsets.UTF_8)));
        try {
            ByteArrayOutputStream err = captureErr();
            var code = DecodeMain.decode(new String[0], DEV_NULL, ps(err));
            assertEquals(1, code);
            assertTrue(str(err).startsWith("decode:"));
        } finally {
            System.setIn(savedIn);
        }
    }
}
