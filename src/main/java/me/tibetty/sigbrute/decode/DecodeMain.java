package me.tibetty.sigbrute.decode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.decode.emit.ConfigEmitter;
import me.tibetty.sigbrute.decode.emit.DecodeConfigValidator;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;

/**
 * Entry point for the {@code decode} subcommand.
 *
 * <p>
 * Usage: {@code sig-brute decode [--strategy greedy|heuristic_search] [--validate]
 * [--ignore-skeleton] [--shallow-skeleton] [--wide] [input-file]}
 * — reads from the file if given, otherwise from stdin. Writes the generated YAML to stdout.
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
        try {
            var parsed = parseArgs(args);
            var input = readInput(parsed.fileArgs());
            if (input == null) {
                return 2;
            }

            var hint = resolveTopLevelHint(input, parsed);
            var result = AbiDecoder.decodeResult(input.body(), hint, parsed.strategy(),
                parsed.wideCandidates(), resolveInlineTopLevelHint(input, parsed));
            for (var warning : result.warnings()) {
                err.println("decode warning: " + warning);
            }

            var yaml = ConfigEmitter.emit(input.selector(), input.methodName(), result);
            if (parsed.validate()) {
                DecodeConfigValidator.validate(input.selector(), yaml);
            }
            out.print(yaml);
            return 0;
        } catch (RuntimeException e) {
            err.println("decode: " + e.getMessage());
            return 1;
        }
    }

    private record ParsedArgs(
        DecodeStrategy strategy,
        boolean validate,
        boolean ignoreSkeleton,
        boolean shallowSkeleton,
        boolean wideCandidates,
        List<String> fileArgs
    ) {
    }

    static List<String> resolveTopLevelHint(CalldataInput input, ParsedArgs parsed) {
        if (parsed.ignoreSkeleton()) {
            return List.of();
        }
        if (parsed.shallowSkeleton()) {
            return input.withShallowSkeleton().topLevelTypes();
        }
        var types = input.topLevelTypes();
        return types != null ? types : List.of();
    }

    static List<String> resolveInlineTopLevelHint(CalldataInput input, ParsedArgs parsed) {
        if (parsed.ignoreSkeleton() || !parsed.shallowSkeleton()) {
            return List.of();
        }
        var types = input.topLevelTypes();
        return types != null ? types : List.of();
    }

    private static ParsedArgs parseArgs(String[] args) {
        var strategy = DecodeStrategy.GREEDY;
        var validate = false;
        var ignoreSkeleton = false;
        var shallowSkeleton = false;
        var wideCandidates = false;
        var rest = new ArrayList<String>();
        var i = 0;
        while (i < args.length) {
            var arg = args[i];
            if ("--strategy".equals(arg) && i + 1 < args.length) {
                strategy = DecodeStrategy.fromId(args[i + 1]);
                i += 2;
            } else if ("--validate".equals(arg)) {
                validate = true;
                i++;
            } else if ("--ignore-skeleton".equals(arg)) {
                ignoreSkeleton = true;
                i++;
            } else if ("--shallow-skeleton".equals(arg)) {
                shallowSkeleton = true;
                i++;
            } else if ("--wide".equals(arg)) {
                wideCandidates = true;
                i++;
            } else {
                rest.add(arg);
                i++;
            }
        }
        if (ignoreSkeleton && shallowSkeleton) {
            throw new IllegalArgumentException(
                "decode: --ignore-skeleton and --shallow-skeleton are mutually exclusive");
        }
        return new ParsedArgs(strategy, validate, ignoreSkeleton, shallowSkeleton, wideCandidates,
            List.copyOf(rest));
    }

    private CalldataInput readInput(List<String> fileArgs) throws IOException {
        String text;
        if (fileArgs.isEmpty()) {
            text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } else {
            text = Files.readString(Path.of(fileArgs.get(0)), StandardCharsets.UTF_8);
        }
        if (text.isBlank()) {
            err.println("decode: input is empty");
            return null;
        }
        return CalldataInput.parse(text);
    }
}
