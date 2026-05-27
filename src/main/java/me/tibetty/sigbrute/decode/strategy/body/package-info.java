/**
 * Tuple-body decoders used when no per-field skeleton is available (or for dynamic tail slices).
 * {@link me.tibetty.sigbrute.decode.strategy.body.GreedyBodyDecoder} is first-fit;
 * {@link me.tibetty.sigbrute.decode.strategy.body.SearchBodyDecoder} branches and picks a winner.
 */
package me.tibetty.sigbrute.decode.strategy.body;
