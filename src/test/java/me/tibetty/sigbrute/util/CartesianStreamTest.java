package me.tibetty.sigbrute.util;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CartesianStreamTest {

    /** Build a Dimension from a plain list. */
    private static <T> Dimension<T> dim(List<T> items) {
        return Dimension.ofList(items);
    }

    // -------------------------------------------------------------------------
    // 1. Sequential iteration produces all combinations in order
    // -------------------------------------------------------------------------
    @Test
    void sequentialIterationProducesAllCombinations() {
        var dims = List.of(dim(List.of("a", "b")), dim(List.of("x", "y", "z")));
        var cs = new CartesianStream<>(dims);

        var results = new ArrayList<List<String>>();
        while (cs.hasNext()) {
            results.add(cs.next());
        }

        assertEquals(6, results.size());
        assertEquals(List.of("a", "x"), results.get(0));
        assertEquals(List.of("a", "y"), results.get(1));
        assertEquals(List.of("a", "z"), results.get(2));
        assertEquals(List.of("b", "x"), results.get(3));
        assertEquals(List.of("b", "y"), results.get(4));
        assertEquals(List.of("b", "z"), results.get(5));
    }

    // -------------------------------------------------------------------------
    // 2. Empty dimension produces no items
    // -------------------------------------------------------------------------
    @Test
    void emptyDimensionProducesNoItems() {
        List<Dimension<String>> dims = List.of(dim(List.of("a", "b")), dim(List.of()) // zero-size
                                                                                      // dimension
        );
        var cs = new CartesianStream<>(dims);
        assertFalse(cs.hasNext());
    }

    // -------------------------------------------------------------------------
    // 3. Single empty combo when dims list is empty
    // -------------------------------------------------------------------------
    @Test
    void singleEmptyCombo() {
        var cs = new CartesianStream<String>(List.of());
        assertTrue(cs.hasNext());
        var combo = cs.next();
        assertTrue(combo.isEmpty());
        assertFalse(cs.hasNext());
    }

    // -------------------------------------------------------------------------
    // 4. Snapshot and resume from middle
    // -------------------------------------------------------------------------
    @Test
    void snapshotAndResumeFromMiddle() {
        // 2×4 = 8 items total
        var dims = List.of(dim(List.of("r", "s")),
            dim(List.of("0", "1", "2", "3")));

        // Collect all 8 expected combos
        var allExpected = new ArrayList<List<String>>();
        var full = new CartesianStream<String>(dims);
        while (full.hasNext()) {
            allExpected.add(full.next());
        }

        // Iterate 3 items from a fresh stream, snapshot, then resume
        var cs = new CartesianStream<String>(dims);
        var first3 = new ArrayList<List<String>>();
        for (int i = 0; i < 3; i++) {
            first3.add(cs.next());
        }
        long[] snap = cs.snapshot();

        // Resume from snapshot
        var resumed = new CartesianStream<String>(dims, snap);
        var remaining = new ArrayList<List<String>>();
        while (resumed.hasNext()) {
            remaining.add(resumed.next());
        }

        assertEquals(3, first3.size());
        assertEquals(5, remaining.size());
        var combined = new ArrayList<List<String>>(first3);
        combined.addAll(remaining);
        assertEquals(allExpected, combined);
    }

    // -------------------------------------------------------------------------
    // 5. Shard covers exact slice (1 dim, 10 values, 4 shards)
    // -------------------------------------------------------------------------
    @Test
    void shardCoversExactSlice() {
        var values = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        var dims = List.of(dim(values));

        // Collect all items per shard
        var shardItems = new ArrayList<List<List<String>>>();
        for (var s = 0; s < 4; s++) {
            var cs = CartesianStream.shard(dims, s, 4);
            var items = new ArrayList<List<String>>();
            while (cs.hasNext()) {
                items.add(cs.next());
            }
            shardItems.add(items);
        }

        // Shards 0..1 get 3 items (10 % 4 = 2, so first 2 shards get 3, rest get 2)
        assertEquals(3, shardItems.get(0).size());
        assertEquals(3, shardItems.get(1).size());
        assertEquals(2, shardItems.get(2).size());
        assertEquals(2, shardItems.get(3).size());

        // Union must equal full space
        var union = new HashSet<String>();
        for (var shard : shardItems) {
            for (var combo : shard) {
                union.add(combo.get(0));
            }
        }
        assertEquals(new HashSet<>(values), union);

        // No overlap between shards
        var seen = new HashSet<String>();
        for (var shard : shardItems) {
            for (var combo : shard) {
                var v = combo.get(0);
                assertFalse(seen.contains(v), "Duplicate item across shards: " + v);
                seen.add(v);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. Shard-to-spliterator parallel produces all items
    // -------------------------------------------------------------------------
    @Test
    void shardToSpliteratorParallelProducesAllItems() {
        // 3×5 = 15 items
        var dims = List.of(dim(List.of("a", "b", "c")),
            dim(List.of("1", "2", "3", "4", "5")));

        var cs = CartesianStream.shard(dims, 0, 1);
        var results = cs.stream(true).toList();

        assertEquals(15, results.size());

        // Verify all 15 unique combos are present
        var resultSet = results.stream().map(c -> c.get(0) + c.get(1))
            .collect(Collectors.toUnmodifiableSet());
        assertEquals(15, resultSet.size());
    }

    // -------------------------------------------------------------------------
    // 7. toBigInteger and fromBigInteger are inverses
    // -------------------------------------------------------------------------
    @Test
    void toBigIntegerAndFromBigIntegerAreInverse() {
        long[] sizes = {3L, 4L, 5L};

        // Test several known positions
        long[][] testIndices = {{0, 0, 0}, {2, 3, 4}, {1, 2, 3}, {0, 0, 4}, {2, 0, 0}};

        for (long[] idx : testIndices) {
            var pos = CartesianStream.toBigInteger(idx, sizes);
            long[] roundTrip = CartesianStream.fromBigInteger(pos, sizes);
            assertArrayEquals(idx, roundTrip,
                "Round-trip failed for indices " + java.util.Arrays.toString(idx));
        }
    }

    // -------------------------------------------------------------------------
    // 8. fromBigInteger decodes correctly
    // -------------------------------------------------------------------------
    @Test
    void fromBigIntegerDecodesCorrectly() {
        // 3×4 space: flat position 7
        // Row-major: row = 7 / 4 = 1, col = 7 % 4 = 3 → indices [1, 3]
        long[] sizes = {3L, 4L};
        long[] indices = CartesianStream.fromBigInteger(BigInteger.valueOf(7), sizes);
        assertArrayEquals(new long[]{1L, 3L}, indices);
    }
}
