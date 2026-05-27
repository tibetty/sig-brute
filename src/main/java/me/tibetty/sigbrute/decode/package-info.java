/**
 * Calldata decoding: {@link me.tibetty.sigbrute.decode.AbiDecoder} is the entry point.
 *
 * <p>Package layout:
 * <ul>
 * <li>{@link me.tibetty.sigbrute.decode.AbiDecoder} — {@link me.tibetty.sigbrute.decode.DecodeResult}
 * via {@code decodeResult}</li>
 * <li>{@link me.tibetty.sigbrute.decode.strategy.DecodeStrategy} — {@code greedy} or
 * {@code heuristic_search}; {@link me.tibetty.sigbrute.decode.strategy.DecodeContext} hooks</li>
 * <li>{@link me.tibetty.sigbrute.decode.layout} — offset tables and dynamic head slots</li>
 * <li>{@link me.tibetty.sigbrute.decode.skeleton} — Etherscan skeleton-guided decode</li>
 * <li>{@link me.tibetty.sigbrute.decode.strategy.body} — heuristic tuple-body parsers</li>
 * <li>{@link me.tibetty.sigbrute.decode.abi} — ABI word/offset/type-string primitives</li>
 * <li>{@link me.tibetty.sigbrute.decode.infer} — per-slot type candidate inference</li>
 * <li>{@link me.tibetty.sigbrute.decode.emit} — YAML emission and validation</li>
 * </ul>
 *
 * <p>Public model types ({@link me.tibetty.sigbrute.decode.DecodedArg},
 * {@link me.tibetty.sigbrute.decode.DecodeResult},
 * {@link me.tibetty.sigbrute.decode.CalldataInput},
 * {@link me.tibetty.sigbrute.decode.SignatureStructure}) remain in this package.
 *
 * <p>See {@code ARCHITECTURE.md} in this directory for the full map.
 */
package me.tibetty.sigbrute.decode;
