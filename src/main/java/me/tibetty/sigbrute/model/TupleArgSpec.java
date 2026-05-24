package me.tibetty.sigbrute.model;

import java.util.List;
import me.tibetty.sigbrute.util.CartesianProduct;
import me.tibetty.sigbrute.util.Dimension;

public record TupleArgSpec(List<ArgSpec> fields, String arraySuffix) implements ArgSpec {

    public TupleArgSpec(List<ArgSpec> fields) {
        this(fields, "");
    }

    @Override
    public Dimension<String> expand() {
        var fieldDims = fields.stream().map(ArgSpec::expand).toList();
        long total = 1L;
        for (Dimension<String> d : fieldDims) {
            total = Math.multiplyExact(total, d.size());
        }
        final long size = total;
        final String suffix = arraySuffix;
        return new Dimension<>() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public String get(long index) {
                return "(" + String.join(",", CartesianProduct.decode(index, fieldDims)) + ")"
                    + suffix;
            }
        };
    }
}
