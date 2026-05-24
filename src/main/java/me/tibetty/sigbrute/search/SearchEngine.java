package me.tibetty.sigbrute.search;

import java.io.PrintStream;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.StreamSupport;
import me.tibetty.sigbrute.model.ArgSpec;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.util.CartesianStream;
import me.tibetty.sigbrute.util.Dimension;
import me.tibetty.sigbrute.util.Keccak256Util;

public class SearchEngine {

    private final SearchConfig config;
    private final PrintStream out;

    public SearchEngine(SearchConfig config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    public List<String> search() {
        // Method names become dimension 0 so the single parallel stream covers the full space,
        // including the case where only one method name is provided.
        var dimensions = new ArrayList<Dimension<String>>();
        dimensions.add(Dimension.ofList(config.methodNames()));
        config.args().stream().map(ArgSpec::expand).forEach(dimensions::add);

        var total = CartesianStream.totalSize(dimensions);
        var nf = NumberFormat.getNumberInstance();

        // In first-match mode, the search is intentionally sequential so that TypeRanker
        // ordering is respected element-by-element and findFirst() terminates as soon as
        // the corpus-optimal prototype is reached. ForkJoin leaf tasks run to completion
        // regardless of findAny/findFirst, so parallel mode cannot short-circuit early.
        var displayThreads = config.findFirst() ? 1 : config.parallelism();
        out.printf("Method candidates : %d%n", config.methodNames().size());
        out.printf("Total search space: %s%n", nf.format(total));
        out.printf("Threads           : %d%n", displayThreads);
        out.printf("Mode              : %s%n%n", config.findFirst()
            ? "first match (sequential, TypeRanker-ordered)" : "all matches (parallel)");

        var counter = new LongAdder();
        long startNs = System.nanoTime();
        var progress = startProgressReporter(out, counter, total);

        var cs = (config.totalShards() > 1)
            ? CartesianStream.shard(dimensions, config.shardIndex(), config.totalShards())
            : new CartesianStream<>(dimensions);

        if (config.findFirst()) {
            // Sequential: TypeRanker dimension ordering is preserved end-to-end.
            // The correct signature is typically at ~0.5% of the space, so this
            // terminates ~200× earlier than an exhaustive parallel scan.
            try {
                var results = StreamSupport.stream(cs.toSpliterator(), false)
                    .map(combo -> {
                        counter.increment();
                        return combo.get(0) + "(" + String.join(",", combo.subList(1, combo.size()))
                            + ")";
                    }).filter(sig -> Keccak256Util.selectorMatches(sig, config.selector()))
                    .findFirst().map(List::of).orElse(List.of());
                progress.interrupt();
                var elapsedSec = (System.nanoTime() - startNs) / 1e9;
                out.printf("%nChecked %s / %s in %.1f s — %d match(es) found.%n",
                    nf.format(counter.longValue()), nf.format(total), elapsedSec, results.size());
                return results;
            } finally {
                progress.interrupt();
            }
        }

        // All-matches: parallel scan across the full space.
        var pool = new ForkJoinPool(config.parallelism());
        try {
            var results = pool
                .submit(() -> StreamSupport.stream(cs.toSpliterator(), true).map(combo -> {
                    counter.increment();
                    return combo.get(0) + "(" + String.join(",", combo.subList(1, combo.size()))
                        + ")";
                }).filter(sig -> Keccak256Util.selectorMatches(sig, config.selector())).toList())
                .get();

            progress.interrupt();
            var elapsedSec = (System.nanoTime() - startNs) / 1e9;
            out.printf("%nChecked %s / %s in %.1f s — %d match(es) found.%n",
                nf.format(counter.longValue()), nf.format(total), elapsedSec, results.size());
            return results;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.interrupt();
            throw new SearchException("Search interrupted", e);
        } catch (ExecutionException e) {
            progress.interrupt();
            var cause = e.getCause() != null ? e.getCause() : e;
            throw new SearchException("Search failed", cause);
        } finally {
            pool.shutdown();
        }
    }

    private static Thread startProgressReporter(PrintStream out, LongAdder counter,
        BigInteger total) {
        var t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1_000);

                    var done = counter.longValue();
                    var pct = total.signum() > 0 ? done * 100.0 / total.doubleValue() : 0.0;
                    out.printf("\r  %,d / %s  (%.1f%%)   ", done,
                        NumberFormat.getNumberInstance().format(total), pct);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
