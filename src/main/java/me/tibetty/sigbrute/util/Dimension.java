package me.tibetty.sigbrute.util;

import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * A re-iterable, randomly-addressable sequence of values used as one axis of a Cartesian product.
 * Values are produced on demand by {@link #get(long)} — no eager materialisation, so a dimension
 * with billions of entries costs nothing until enumerated.
 *
 * <p>
 * Long-indexed (not int-indexed) so nested tuple expansions are not capped at {@code
 * Integer.MAX_VALUE}.
 */
public interface Dimension<T> {

    long size();

    T get(long index);

    default Stream<T> stream() {
        return LongStream.range(0, size()).mapToObj(this::get);
    }

    static <T> Dimension<T> ofList(List<T> list) {
        return new Dimension<>() {
            @Override
            public long size() {
                return list.size();
            }

            @Override
            public T get(long index) {
                return list.get(Math.toIntExact(index));
            }

            @Override
            public Stream<T> stream() {
                return list.stream();
            }
        };
    }
}
