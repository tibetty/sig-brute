/**
 * Skeleton-guided decoding when Etherscan / 4byte {@code Function:} type hints are available.
 *
 * <p><b>Skeleton-only</b> (explorer hints with opaque {@code tuple} / {@code tuple[]} only, no
 * inline {@code (T,...)}): {@link me.tibetty.sigbrute.decode.ShallowSkeletonHints#isSkeletonOnlyLayout},
 * {@link SkeletonOnlyStaticTuplePartitioner} (static head tuple runs),
 * {@link SkeletonArrayDecoder#decodeOpaqueTupleArray} ({@link SkeletonTypeKind#OPAQUE_TUPLE_ARRAY}),
 * and greedy opaque tuple bodies on the active
 * {@link me.tibetty.sigbrute.decode.strategy.DecodeContext}.
 */
package me.tibetty.sigbrute.decode.skeleton;
