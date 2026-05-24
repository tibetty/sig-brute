package me.tibetty.sigbrute.model;

import java.util.List;
import me.tibetty.sigbrute.expander.TypeExpander;
import me.tibetty.sigbrute.expander.TypeRanker;
import me.tibetty.sigbrute.util.Dimension;

public record LeafArgSpec(List<String> patterns) implements ArgSpec {
    /**
     * Expands all patterns, deduplicates, and sorts by {@link TypeRanker} frequency (most-probable
     * type first).
     *
     * <p>
     * Ordering is a no-op for all-matches mode (every candidate is visited regardless) and a
     * significant speedup for {@code find_first} mode: the search engine encounters the most-common
     * ABI types first, so the expected time-to-first-match drops proportionally to how well the
     * prior matches the actual signature.
     */
    @Override
    public Dimension<String> expand() {
        var values = patterns.stream().flatMap(p -> TypeExpander.expand(p).stream())
            .distinct().sorted(TypeRanker.comparator()).toList();
        return Dimension.ofList(values);
    }
}
