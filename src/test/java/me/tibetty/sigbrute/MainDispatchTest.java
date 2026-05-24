package me.tibetty.sigbrute;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainDispatchTest {

    private static String runCapturingStdout(String... args) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Main.run(args, ps, System.err);
        return buf.toString(StandardCharsets.UTF_8);
    }

    // ── decode subcommand ─────────────────────────────────────────────────────

    @Test
    void decodeSubcommand_dispatchesToDecodeMain() throws Exception {
        var path = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata")
            .toAbsolutePath().toString();
        var output = runCapturingStdout("decode", path);
        assertTrue(output.contains("selector:"));
        assertTrue(output.contains("method_names:"));
    }

    // ── --find-first flag ─────────────────────────────────────────────────────

    /**
     * {@code --find-first} placed before the config path must be stripped so the remaining
     * positional arg is treated as the config file. A missing file produces {@link
     * java.io.FileNotFoundException} — which confirms the flag was accepted and parsing reached the
     * file-open step.
     */
    @Test
    void findFirstFlag_beforeConfigPath_isAccepted() {
        assertThrows(java.io.FileNotFoundException.class,
            () -> Main.run(new String[]{"--find-first", "/nonexistent_config_xyz.yaml"}, System.out,
                System.err));
    }

    /** {@code --find-first} placed after the config path must also be stripped. */
    @Test
    void findFirstFlag_afterConfigPath_isAccepted() {
        assertThrows(java.io.FileNotFoundException.class,
            () -> Main.run(new String[]{"/nonexistent_config_xyz.yaml", "--find-first"}, System.out,
                System.err));
    }

    /**
     * With no positional args (only a flag), {@code run()} must return exit code 1 and write usage
     * to stderr — not throw.
     */
    @Test
    void noArgs_returnsUsageExitCode() throws Exception {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        var status = Main.run(new String[]{"--find-first"}, System.out, err);
        assertEquals(1, status);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }
}
