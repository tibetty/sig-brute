package me.tibetty.sigbrute;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import me.tibetty.sigbrute.decode.DecodeMain;
import me.tibetty.sigbrute.lookup.HttpSignatureLookup;
import me.tibetty.sigbrute.lookup.SignaturePreflight;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.parser.YamlConfigParser;
import me.tibetty.sigbrute.search.SearchEngine;
import me.tibetty.sigbrute.search.SearchException;

public class Main {

    /**
     * Compiled pattern for all recognised ANSI/VT escape sequences:
     * <ul>
     *   <li>CSI (ESC 0x5B ...): ESC [ ... final-byte</li>
     *   <li>OSC/DCS/PM/APC (ESC 0x5D/0x50/0x5E/0x5F ...): lazy payload terminated by BEL or ST (ESC \)</li>
     *   <li>Fe/Fp bare ESC+letter (e.g. ESC c = full reset, ESC M = reverse index)</li>
     * </ul>
     */
    private static final Pattern ANSI_ESCAPE = Pattern.compile(
        // CSI: ESC [ ... final byte
        "\\x1B(?:\\[[;:\\d]*[A-Za-z]"
        // OSC/DCS/PM/APC: ESC ] ^ _ P ... payload ... BEL or ST (ESC \)
        + "|[\\]\\^_P](?:[^\\x07\\x1B]|\\x1B(?![\\\\]))*(?:\\x07|\\x1B\\\\)"
        // Fe/Fp bare: ESC <letter> (e.g. ESC c, ESC M, ESC 7)
        + "|[A-Za-z])");

    /** CLI entry point — calls {@link System#exit} with the status returned by {@link #run}. */
    public static void main(String[] args) throws IOException {
        var status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Core logic. Returns an exit status (0 = success, non-zero = error) without calling {@link
     * System#exit}, making it fully testable.
     *
     * @param args
     *            CLI arguments
     * @param out
     *            stream for normal output
     * @param err
     *            stream for error/usage messages
     */
    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length >= 1 && args[0].equals("decode")) {
            return DecodeMain.decode(Arrays.copyOfRange(args, 1, args.length), out, err);
        }

        var cli = parseSearchCli(args);
        if (cli.positional().isEmpty()) {
            printUsage(err);
            return 1;
        }

        var config = loadSearchConfig(cli.positional().get(0));
        config = withFindFirstOverride(config, cli.findFirst());

        var lookupExit = tryFinishFromLookup(config, cli.skipLookup(), out, err);
        if (lookupExit != null) {
            return lookupExit;
        }

        return runSearch(config, out, err);
    }

    private record SearchCli(boolean findFirst, boolean skipLookup, List<String> positional) {
    }

    private static SearchCli parseSearchCli(String[] args) {
        var findFirst = false;
        var skipLookup = false;
        var positional = new ArrayList<String>();
        for (String arg : args) {
            if (arg.equals("--find-first")) {
                findFirst = true;
            } else if (arg.equals("--skip-lookup")) {
                skipLookup = true;
            } else {
                positional.add(arg);
            }
        }
        return new SearchCli(findFirst, skipLookup, positional);
    }

    private static void printUsage(PrintStream err) {
        err.println("Usage:");
        err.println("  sig-brute [--find-first] [--skip-lookup] <config.yaml>   brute-force search");
        err.println("  sig-brute decode [--strategy greedy|heuristic_search] [--validate]"
            + " [--shallow-skeleton] [input]   generate YAML from calldata");
        err.println();
        err.println("Options:");
        err.println("  --find-first    stop after the first matching signature (overrides YAML)");
        err.println("  --skip-lookup   skip 4byte.directory / Sourcify pre-search lookup");
        err.println("                  (without this flag the 4-byte selector is sent to public");
        err.println("                   signature databases before brute-force search begins)");
        err.println("  --validate      (decode) parse emitted YAML before writing stdout");
    }

    private static SearchConfig loadSearchConfig(String configArg) throws IOException {
        if (configArg.equals("-")) {
            return new YamlConfigParser().parse(System.in);
        }
        try (var fis = new FileInputStream(configArg)) {
            return new YamlConfigParser().parse(fis);
        }
    }

    private static SearchConfig withFindFirstOverride(SearchConfig config, boolean findFirst) {
        if (!findFirst || config.findFirst()) {
            return config;
        }
        return new SearchConfig(config.selector(), config.methodNames(), config.args(),
            config.parallelism(), true, config.shardIndex(), config.totalShards());
    }

    /**
     * @return exit code when lookup short-circuits search, or {@code null} to continue
     */
    private static Integer tryFinishFromLookup(SearchConfig config, boolean skipLookup,
        PrintStream out, PrintStream err) {
        var result = SignaturePreflight.run(config.selector(), new HttpSignatureLookup(),
            skipLookup);

        // Surface diagnostics (warnings / transient errors) before any further output.
        result.diagnostics().forEach(err::println);

        if (result.hasMatches()) {
            out.println("Known signatures (4byte / Sourcify):");
            // Sanitize before printing: strip ANSI escape sequences and control characters
            // from API-returned strings to prevent terminal injection (OWASP A03).
            result.verified().forEach(s -> out.println("  " + sanitizeForTerminal(s)));
            out.println();
        }

        if (!config.findFirst() || !result.hasMatches()) {
            return null;
        }
        // All entries in result.verified() were already Keccak-verified in SignaturePreflight.
        out.println("First verified lookup match (find_first — search skipped):");
        out.println("  " + sanitizeForTerminal(result.verified().get(0)));
        return 0;
    }

    /**
     * Strips all ANSI/VT escape sequences and ASCII control characters from API-returned strings
     * before printing to the terminal.
     *
     * <p>
     * Prevents terminal injection: a compromised or adversarial upstream API could embed escape
     * sequences (CSI colour codes, OSC window-title changes, DCS/PM/APC payloads, Fe/Fp bare
     * ESC+letter resets) that overwrite terminal output or hide content (OWASP A03).
     */
    static String sanitizeForTerminal(String s) {
        // Strip all recognised ANSI/VT escape sequences
        var stripped = ANSI_ESCAPE.matcher(s).replaceAll("");
        // Strip remaining C0 control characters (0x00-0x1F) and DEL (0x7F)
        return stripped.replaceAll("[\\x00-\\x1F\\x7F]", "");
    }

    private static int runSearch(SearchConfig config, PrintStream out, PrintStream err) {
        List<String> results;
        try {
            results = new SearchEngine(config, out).search();
        } catch (SearchException e) {
            err.println("search: " + e.getMessage());
            return 1;
        }

        if (results.isEmpty()) {
            out.println("No matching signatures found.");
        } else {
            out.println("\nMatching signatures:");
            results.forEach(s -> out.println("  " + s));
        }
        return 0;
    }
}
