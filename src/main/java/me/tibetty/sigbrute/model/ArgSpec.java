package me.tibetty.sigbrute.model;

import me.tibetty.sigbrute.util.Dimension;

public sealed interface ArgSpec permits LeafArgSpec, TupleArgSpec {
    /** Returns all concrete ABI type strings this position can resolve to, on demand. */
    Dimension<String> expand();
}
