package me.tibetty.sigbrute.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public final class CartesianProduct {

    private CartesianProduct() {
    }

    /**
     * Returns a lazy, splittable stream of all combinations across the given dimensions.
     *
     * <p>
     * Index-based decoding: for index i, decode which combination it represents by repeatedly
     * taking modulo and dividing across each dimension (right to left). Nothing is materialised up
     * front; LongStream.range splits evenly for .parallel().
     *
     * <p>
     * Each dimension's values are produced via {@link Dimension#get(long)} — so a dimension that
     * is itself a nested Cartesian product (e.g. a tuple expansion) generates its outputs on demand
     * without materialising them either.
     *
     * <p>
     * If the total Cartesian product size overflows {@code long}, falls through to a {@link
     * CartesianStream} sequential iterator rather than throwing.
     */
    public static <T> Stream<List<T>> lazyStream(List<Dimension<T>> dims) {
        if (dims.isEmpty()) {
            return Stream.of(Collections.emptyList());
        }
        for (Dimension<T> dim : dims) {
            if (dim.size() == 0) {
                return Stream.empty();
            }
        }

        var totalSize = 1L;
        var overflowed = false;
        for (Dimension<T> dim : dims) {
            try {
                totalSize = Math.multiplyExact(totalSize, dim.size());
            } catch (ArithmeticException e) {
                overflowed = true;
                break;
            }
        }

        if (overflowed) {
            return new CartesianStream<>(dims).stream(false);
        }

        var frozen = List.copyOf(dims);
        return LongStream.range(0, totalSize).mapToObj(i -> decode(i, frozen));
    }

    public static <T> List<T> decode(long index, List<Dimension<T>> dims) {
        var combo = new ArrayList<T>(dims.size());
        var remaining = index;
        for (var i = dims.size() - 1; i >= 0; i--) {
            var size = dims.get(i).size();
            combo.add(0, dims.get(i).get(remaining % size));
            remaining /= size;
        }
        return Collections.unmodifiableList(combo);
    }
}
