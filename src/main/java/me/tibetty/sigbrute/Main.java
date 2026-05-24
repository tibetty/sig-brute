package me.tibetty.sigbrute;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.tibetty.sigbrute.decode.DecodeMain;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.parser.YamlConfigParser;
import me.tibetty.sigbrute.search.SearchEngine;
import me.tibetty.sigbrute.search.SearchException;

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

        // ── Flag parsing ─────────────────────────────────────────────────────
        var flagFindFirst = false;
        var positional = new ArrayList<String>();
        for (String arg : args) {
            if (arg.equals("--find-first")) {
                flagFindFirst = true;
            } else {
                positional.add(arg);
            }
        }

        if (positional.isEmpty()) {
            err.println("Usage:");
            err.println(
                "  sig-brute [--find-first] <config.yaml>   brute-force search ('-' reads from stdin)");
            err.println("  sig-brute decode [input]"
                + "                 generate YAML from calldata (stdin if [input] omitted)");
            err.println();
            err.println("Options:");
            err.println(
                "  --find-first   stop after the first matching signature (overrides YAML find_first)");
            return 1;
        }

        // ── Load config ──────────────────────────────────────────────────────
        var configArg = positional.get(0);
        SearchConfig config;
        if (configArg.equals("-")) {
            config = new YamlConfigParser().parse(System.in);
        } else {
            try (var fis = new FileInputStream(configArg)) {
                config = new YamlConfigParser().parse(fis);
            }
        }

        // CLI flag overrides YAML find_first when explicitly supplied.
        if (flagFindFirst && !config.findFirst()) {
            config = new SearchConfig(config.selector(), config.methodNames(), config.args(),
                config.parallelism(), true, config.shardIndex(), config.totalShards());
        }

        // ── Search ───────────────────────────────────────────────────────────
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
