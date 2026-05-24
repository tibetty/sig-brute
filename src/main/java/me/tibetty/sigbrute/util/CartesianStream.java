package me.tibetty.sigbrute.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Stateful mixed-radix iterator (odometer) over a Cartesian product of Dimensions.
 *
 * <p>
 * Internal state: {@code indices[n]} (current position per dimension) and {@code sizes[n]}
 * (dimension sizes). {@link #next()} advances the rightmost index and carries left — no
 * materialisation, no Long.MAX_VALUE cap on total space.
 *
 * <p>
 * BigInteger is used only for:
 *
 * <ul>
 * <li>{@link #totalSize()} — product of all dimension sizes (for display / sharding)
 * <li>{@link #shard} — slice boundary arithmetic
 * <li>{@link CartesianSpliterator#trySplit} — midpoint computation (called O(log p) times)
 * </ul>
 *
 * The {@link #next()} hot path uses only {@code long} arithmetic.
 *
 * <h3>Usage patterns</h3>
 *
 * <pre>{@code
 * // Sequential scan
 * var cs = new CartesianStream<>(dims);
 * while (cs.hasNext())
 *     process(cs.next());
 *
 * // Checkpoint then resume
 * long[] saved = cs.snapshot();
 * var cs2 = new CartesianStream<>(dims, saved);
 *
 * // Parallel Java stream (ForkJoin splits via Spliterator)
 * StreamSupport.stream(new CartesianStream<>(dims).toSpliterator(), true);
 *
 * // Multi-node: node 2 of 5 nodes
 * var cs3 = CartesianStream.shard(dims, 2, 5);
 * }</pre>
 */
public final class CartesianStream<T> implements Iterator<List<T>> {

    // --- fields ---
    private final List<Dimension<T>> dims;
    final long[] sizes; // package-visible for CartesianSpliterator
    private final long[] indices; // current mixed-radix position; mutable
    private boolean done; // true when advanceIndices() wrapped to all-zeros
    private long remaining; // item countdown; Long.MAX_VALUE = unlimited

    // --- Constructors ---

    /** Full scan from position zero. */
    public CartesianStream(List<Dimension<T>> dims) {
        this(dims, new long[dims.size()], null);
    }

    /**
     * Resume scan from {@code startIndices} to the end of the space. {@code remaining} is set to
     * {@code totalSize − startPos}, saturated to {@code Long.MAX_VALUE} for astronomically large
     * spaces.
     */
    public CartesianStream(List<Dimension<T>> dims, long[] startIndices) {
        this(dims, startIndices, null);
    }

    /**
     * Shard constructor: start at {@code startIndices} and emit at most {@code limit} items ({@code
     * null} means "scan to end of space, same as single-arg ctor").
     */
    public CartesianStream(List<Dimension<T>> dims, long[] startIndices, BigInteger limit) {
        this.dims = List.copyOf(dims);
        this.sizes = dims.stream().mapToLong(Dimension::size).toArray();
        this.indices = startIndices.clone();

        if (hasZeroDim(this.sizes)) {
            this.done = true;
            this.remaining = 0;
        } else if (dims.isEmpty()) {
            // Empty dims: exactly one empty combo (the "unit" of Cartesian product)
            this.done = false;
            this.remaining = (limit != null) ? saturateLong(limit) : 1L;
        } else {
            this.done = false;
            if (limit != null) {
                this.remaining = saturateLong(limit);
            } else {
                // remaining = totalSize - startPos, saturated
                var total = totalSize(dims);
                var startPos = toBigInteger(startIndices, this.sizes);
                var rem = total.subtract(startPos);
                this.remaining = saturateLong(rem);
            }
        }
    }

    // --- Iterator ---

    @Override
    public boolean hasNext() {
        return !done && remaining > 0;
    }

    @Override
    public List<T> next() {
        if (!hasNext()) {
            throw new java.util.NoSuchElementException();
        }

        // Build combo from current indices
        var combo = new ArrayList<T>(dims.size());
        for (var i = 0; i < dims.size(); i++) {
            combo.add(dims.get(i).get(indices[i]));
        }

        // Advance indices; if it returns true (full wrap) set done
        if (dims.isEmpty() || advanceIndices(indices, sizes)) {
            done = true;
        }
        if (remaining != Long.MAX_VALUE) {
            remaining--;
        }
        return Collections.unmodifiableList(combo);
    }

    // --- Checkpoint ---

    /** Snapshot current position for checkpointing; pass to resume constructor. */
    public long[] snapshot() {
        return indices.clone();
    }

    /** Total size of the entire search space (product of all dimension sizes). */
    public BigInteger totalSize() {
        return totalSize(dims);
    }

    /**
     * Items remaining to emit. {@code Long.MAX_VALUE} means unlimited or space exceeds {@code long}
     * range.
     */
    public long remaining() {
        return remaining;
    }

    // --- Parallel stream ---

    /**
     * Returns a Spliterator suitable for parallel Java streams. The Spliterator supports binary
     * splitting for balanced parallelism.
     */
    public Spliterator<List<T>> toSpliterator() {
        var lo = toBigInteger(indices, sizes);
        var hi = (remaining == Long.MAX_VALUE) ? totalSize(dims)
            : lo.add(BigInteger.valueOf(remaining));
        return new CartesianSpliterator<>(dims, sizes, indices.clone(), hi, remaining);
    }

    /** Wraps this iterator as a sequential (parallel=false) or parallel Java Stream. */
    public Stream<List<T>> stream(boolean parallel) {
        return StreamSupport.stream(toSpliterator(), parallel);
    }

    // --- Multi-node sharding ---

    /**
     * Creates a stream covering shard {@code shardIndex} (0-based) out of {@code totalShards}. The
     * search space is divided into contiguous equal-sized slices via BigInteger arithmetic; first
     * {@code (total % totalShards)} shards each get one extra item.
     *
     * @throws IllegalArgumentException
     *             if shardIndex out of [0, totalShards)
     */
    public static <T> CartesianStream<T> shard(List<Dimension<T>> dims, int shardIndex,
        int totalShards) {
        if (shardIndex < 0 || shardIndex >= totalShards) {
            throw new IllegalArgumentException(
                "shardIndex " + shardIndex + " out of range [0, " + totalShards + ")");
        }

        var sizes = dims.stream().mapToLong(Dimension::size).toArray();
        var total = totalSize(dims);
        var n = BigInteger.valueOf(totalShards);
        var i = BigInteger.valueOf(shardIndex);
        var base = total.divide(n);
        var extra = total.mod(n);

        var start = base.multiply(i).add(i.min(extra));
        var count = base.add(i.compareTo(extra) < 0 ? BigInteger.ONE : BigInteger.ZERO);
        return new CartesianStream<>(dims, fromBigInteger(start, sizes), count);
    }

    // --- BigInteger <-> mixed-radix ---

    /**
     * Converts a flat BigInteger position to a mixed-radix indices array. Left dimension is most
     * significant (same order as dims).
     */
    public static long[] fromBigInteger(BigInteger pos, long[] sizes) {
        var idx = new long[sizes.length];
        for (var i = sizes.length - 1; i >= 0; i--) {
            var sz = BigInteger.valueOf(sizes[i]);
            idx[i] = pos.mod(sz).longValueExact();
            pos = pos.divide(sz);
        }
        return idx;
    }

    /** Converts a mixed-radix indices array to a flat BigInteger position. */
    public static BigInteger toBigInteger(long[] indices, long[] sizes) {
        var pos = BigInteger.ZERO;
        for (var i = 0; i < sizes.length; i++) {
            pos = pos.multiply(BigInteger.valueOf(sizes[i])).add(BigInteger.valueOf(indices[i]));
        }
        return pos;
    }

    // --- Package-visible helpers (used by inner CartesianSpliterator) ---

    public static BigInteger totalSize(List<? extends Dimension<?>> dims) {
        var t = BigInteger.ONE;
        for (Dimension<?> d : dims) {
            t = t.multiply(BigInteger.valueOf(d.size()));
        }
        return t;
    }

    static boolean hasZeroDim(long[] sizes) {
        for (long s : sizes) {
            if (s == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Increments mixed-radix indices in place (rightmost first). Returns {@code true} if all
     * indices wrapped back to zero (space exhausted).
     */
    static boolean advanceIndices(long[] indices, long[] sizes) {
        for (int i = sizes.length - 1; i >= 0; i--) {
            if (++indices[i] < sizes[i]) {
                return false;
            }
            indices[i] = 0;
        }
        return true; // full wrap-around
    }

    static long saturateLong(BigInteger v) {
        return v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE
            : v.longValueExact();
    }

    // --- Inner Spliterator ---

    /**
     * Spliterator backed by a mixed-radix cursor. Supports binary splitting for ForkJoin parallel
     * streams.
     *
     * <p>
     * Hot path ({@link #tryAdvance}): pure {@code long} arithmetic on {@code indices[]}. Split
     * path ({@link #trySplit}): O(dims) BigInteger ops, called at most O(log parallelism) times.
     */
    static final class CartesianSpliterator<T> implements Spliterator<List<T>> {

        private final List<Dimension<T>> dims;
        private final long[] sizes;
        private long[] indices; // current position (mutable; cloned on split)
        private long remaining; // item countdown; Long.MAX_VALUE = unlimited/huge
        private boolean done; // wrap-around flag (backup terminator for huge spaces)
        private final BigInteger hiBI; // exclusive end, for accurate trySplit arithmetic

        CartesianSpliterator(List<Dimension<T>> dims, long[] sizes, long[] startIndices,
            BigInteger hiBI, long remaining) {
            this.dims = dims;
            this.sizes = sizes;
            this.indices = startIndices.clone();
            this.hiBI = hiBI;
            this.remaining = remaining;
            this.done = CartesianStream.hasZeroDim(sizes);
        }

        @Override
        public boolean tryAdvance(Consumer<? super List<T>> action) {
            if (done || remaining == 0) {
                return false;
            }
            var combo = new ArrayList<T>(dims.size());
            for (int i = 0; i < dims.size(); i++) {
                combo.add(dims.get(i).get(indices[i]));
            }
            if (dims.isEmpty() || CartesianStream.advanceIndices(indices, sizes)) {
                done = true;
            }
            if (remaining != Long.MAX_VALUE) {
                remaining--;
            }
            action.accept(Collections.unmodifiableList(combo));
            return true;
        }

        @Override
        public Spliterator<List<T>> trySplit() {
            if (done || remaining <= 1) {
                return null;
            }
            // Compute current flat position (BigInteger; called O(log p) times total)
            var curBI = CartesianStream.toBigInteger(indices, sizes);
            if (curBI.compareTo(hiBI) >= 0) {
                return null;
            }
            var remBI = hiBI.subtract(curBI);
            if (remBI.compareTo(BigInteger.TWO) < 0) {
                return null;
            }
            var leftBI = remBI.divide(BigInteger.TWO);
            var midBI = curBI.add(leftBI);
            var leftLong = CartesianStream.saturateLong(leftBI);
            var rightLong = (remaining == Long.MAX_VALUE) ? Long.MAX_VALUE : remaining - leftLong;

            // Left half: snapshot current indices, limited to leftLong items
            var left = new CartesianSpliterator<>(dims, sizes, indices, midBI,
                leftLong);

            // This spliterator becomes the right half: advance to midpoint
            this.indices = CartesianStream.fromBigInteger(midBI, sizes);
            this.remaining = rightLong;

            return left;
        }

        @Override
        public long estimateSize() {
            return remaining;
        }

        @Override
        public int characteristics() {
            int base = ORDERED | IMMUTABLE | NONNULL;
            if (remaining != Long.MAX_VALUE) {
                base |= SIZED | SUBSIZED;
            }
            return base;
        }
    }
}
