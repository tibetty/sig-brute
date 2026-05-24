package me.tibetty.sigbrute.decode;

import java.io.IOException;

import me.tibetty.sigbrute.decode.emit.ConfigEmitter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the {@code decode} subcommand.
 *
 * <p>
 * Usage: {@code sig-brute decode [input-file]} — reads from the file if given, otherwise from
 * stdin. Writes the generated YAML to stdout.
 */
public final class DecodeMain {

    private final PrintStream out;
    private final PrintStream err;

    public DecodeMain(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Runs decode logic and returns an exit status without calling {@link System#exit}.
     *
     * @param args
     *            CLI arguments (empty → read from {@link System#in})
     * @param out
     *            stream for generated YAML output
     * @param err
     *            stream for error messages
     * @return {@code 0} success, {@code 1} parse/decode error, {@code 2} empty input
     */
    public static int decode(String[] args, PrintStream out, PrintStream err) throws IOException {
        return new DecodeMain(out, err).decode(args);
    }

    /**
     * Instance decode — prefer {@link #decode(String[], PrintStream, PrintStream)} at call sites.
     *
     * @param args
     *            CLI arguments (empty → read from {@link System#in})
     * @return {@code 0} success, {@code 1} parse/decode error, {@code 2} empty input
     */
    public int decode(String[] args) throws IOException {
        String text;
        if (args.length == 0) {
            text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } else {
            text = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        }
        if (text.isBlank()) {
            err.println("decode: input is empty");
            return 2;
        }

        try {
            var input = CalldataInput.parse(text);
            var decoded = AbiDecoder.decodeArgs(input.body(), input.topLevelTypes());
            var yaml = ConfigEmitter.emit(input.selector(), input.methodName(), decoded);
            out.print(yaml);
            return 0;
        } catch (RuntimeException e) {
            err.println("decode: " + e.getMessage());
            return 1;
        }
    }
}
