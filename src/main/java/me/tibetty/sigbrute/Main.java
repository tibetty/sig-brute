package me.tibetty.sigbrute;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.tibetty.sigbrute.decode.DecodeMain;
import me.tibetty.sigbrute.lookup.HttpSignatureLookup;
import me.tibetty.sigbrute.lookup.SignaturePreflight;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.parser.YamlConfigParser;
import me.tibetty.sigbrute.search.SearchEngine;
import me.tibetty.sigbrute.search.SearchException;
import me.tibetty.sigbrute.util.Keccak256Util;

public class Main {

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
            + " [input]   generate YAML from calldata");
        err.println();
        err.println("Options:");
        err.println("  --find-first    stop after the first matching signature (overrides YAML)");
        err.println("  --skip-lookup   skip 4byte.directory / Sourcify pre-search lookup");
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
        var known = SignaturePreflight.run(config.selector(), new HttpSignatureLookup(), out, err,
            skipLookup);
        if (!config.findFirst() || known.isEmpty()) {
            return null;
        }
        var first = known.get(0);
        if (!Keccak256Util.selectorMatches(first, config.selector())) {
            return null;
        }
        out.println("First verified lookup match (find_first — search skipped):");
        out.println("  " + first);
        return 0;
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
