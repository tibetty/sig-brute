/**
 * Decode strategy façade: {@link me.tibetty.sigbrute.decode.strategy.DecodeStrategy} selects how
 * tuple bodies are parsed. {@link me.tibetty.sigbrute.decode.strategy.DecodeContext} carries the
 * active body decoder, dynamic-tail decoder, and slot inferrer through each call.
 *
 * <p>Variants are {@link me.tibetty.sigbrute.decode.strategy.DecodeStrategy} enum constants; shared
 * body algorithms are in {@link me.tibetty.sigbrute.decode.strategy.body}.
 */
package me.tibetty.sigbrute.decode.strategy;
