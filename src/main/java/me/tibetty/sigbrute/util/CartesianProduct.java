package me.tibetty.sigbrute.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CartesianProduct {

    private CartesianProduct() {
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
