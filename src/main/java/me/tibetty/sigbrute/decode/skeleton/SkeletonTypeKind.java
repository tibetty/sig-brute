package me.tibetty.sigbrute.decode.skeleton;

/** How a skeleton type string is routed through {@link SkeletonDecoder}. */
public enum SkeletonTypeKind {
    /** {@code bytes} or {@code string}. */
    DYNAMIC_SCALAR,
    /** {@code address[]}, {@code uint256[][]}, etc. (not inline tuple). */
    DYNAMIC_PRIM_ARRAY,
    /** {@code (T,...)[…]} with dynamic {@code []} — tail decoded on the dynamic path. */
    INLINE_TUPLE_ARRAY,
    /** Skeleton-only opaque {@code tuple[]}, {@code tuple[][]}, … (no inline field types). */
    OPAQUE_TUPLE_ARRAY,
    /** Skeleton-only opaque {@code tuple} (static or dynamic head). */
    OPAQUE_TUPLE,
    /** Static head slots (primitives, fixed arrays, static inline tuples). */
    STATIC
}
