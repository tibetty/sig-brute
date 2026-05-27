package me.tibetty.sigbrute.decode;

import static me.tibetty.sigbrute.decode.abi.AbiTestEncoder.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import me.tibetty.sigbrute.decode.abi.AbiTestEncoder.Val;
import me.tibetty.sigbrute.decode.emit.ConfigEmitter;
import me.tibetty.sigbrute.parser.YamlConfigParser;
import me.tibetty.sigbrute.search.SearchEngine;
import me.tibetty.sigbrute.util.Keccak256Util;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Round-trip property test: build calldata → decode → emit YAML → search → assert the original
 * canonical signature is in the results.
 *
 * <p>
 * Two decode modes are exercised:
 *
 * <ul>
 * <li><b>Skeleton-guided</b> — an Etherscan {@code Function:} header is included so the decoder
 * knows top-level types. These cases validate the skeleton routing paths, including the fix
 * for primitive-array mis-identification.
 * <li><b>Heuristic</b> — {@link CalldataInput} is constructed directly with {@code topLevelTypes
 *       = null}. These cases validate TypeInferrer branch coverage, including the {@code bool} fix.
 * </ul>
 *
 * <p>
 * Search-space sizes are kept small (≤ 500 combos) so each parameterised case completes in
 * milliseconds.
 */
class RoundTripTest {

    // ── Test-case source ──────────────────────────────────────────────────────

    static Stream<Arguments> cases() {
        return Stream.of(

            // ── Skeleton-guided cases ──────────────────────────────────────────

            // Regression: 1-element address[] encodes identically to bytes(1) — without
            // the skeleton fix the decoder returned Leaf(["bytes","string"]) and the
            // search never found the correct sig.
            skeletonCase("tokens(address[]) — 1-element, with skeleton", "tokens(address[])",
                staticArray(address("a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"))),

            // Same regression for uint256[].
            skeletonCase("amounts(uint256[]) — 1-element, with skeleton", "amounts(uint256[])",
                staticArray(uint(1_000_000_000_000_000_000L))),

            // Skeleton pins bool to exactly ["bool"] → search space = 1.
            skeletonCase("flag(bool=true) — with skeleton", "flag(bool)", bool(true)),
            skeletonCase("flag(bool=false) — with skeleton", "flag(bool)", bool(false)),

            // Skeleton-guided dynamic bytes.
            skeletonCase("rawData(bytes) — with skeleton", "rawData(bytes)",
                bytes(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF})),

            // Both bool and address[] fixes exercised together.
            skeletonCase("mixed(bool,address[]) — with skeleton, both fixes",
                "mixed(bool,address[])", bool(true),
                staticArray(address("a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"))),

            // ── Heuristic decode cases (no skeleton) ──────────────────────────

            // bool(false) = all-zero slot: TypeInferrer must include "bool" in candidates.
            // Fix: zero-slot candidates changed from ["uint*","address","bytes32"] to
            // ["uint*","address","bytes32","bool"] (35 combos).
            heuristicCase("flag(bool=false) — heuristic, TypeInferrer zero-slot", "flag",
                "flag(bool)", bool(false).word()),

            // bool(true) = value-1 slot: TypeInferrer must include "bool" in candidates.
            // Fix: firstNZ=31 && word[31]==1 → ["uint*","bool"] (33 combos).
            heuristicCase("flag(bool=true) — heuristic, TypeInferrer value-1 slot", "flag",
                "flag(bool)", bool(true).word()),

            // address + uint256 with heuristic decode — validates TypeInferrer address
            // and uint-narrowing paths (14 × 25 = 350 combos).
            heuristicCase("transfer(address,uint256) — heuristic", "transfer",
                "transfer(address,uint256)",
                concat(address("a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
                    uint(1_000_000_000_000_000_000L))),

            // ── Heuristic bytes/array ambiguity: length-1 cases ───────────────
            //
            // bytes(1) and T[](1) have the same 64-byte layout (length word = 1,
            // then 32 bytes of content).  Without the fix, tryDecodeLengthPrefixedBytes
            // wins for both and the array signature is never found.
            //
            // Fix guard: bytes(1) with non-zero content (0xDE, left-aligned) must
            // still be decoded as bytes, not address[].
            heuristicCase("rawData(bytes) — 1 byte, heuristic (bytes-vs-array guard)", "rawData",
                "rawData(bytes)",
                singleDynBody(bytes(new byte[]{(byte) 0xDE}))),

            // Fix case: 1-element address[] must not be decoded as bytes.
            heuristicCase("tokens(address[]) — 1-element, heuristic (bytes/array ambiguity)",
                "tokens", "tokens(address[])",
                singleDynBody(staticArray(address("a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")))),

            // Fix case: 1-element uint256[] must not be decoded as bytes.
            heuristicCase("amounts(uint256[]) — 1-element, heuristic (bytes/array ambiguity)",
                "amounts", "amounts(uint256[])",
                singleDynBody(staticArray(uint(1_000_000_000_000_000_000L)))));
    }

    // ── Case builders ─────────────────────────────────────────────────────────

    /**
     * Skeleton-guided round-trip: the calldata is built from {@code args} and a {@code Function:}
     * header is included so the decoder uses skeleton typing. The same string is used for both the
     * canonical and skeleton signatures, which is valid as long as {@code sig} contains no nested
     * parentheses.
     */
    private static Arguments skeletonCase(String label, String sig, Val... args) {
        var dump = etherscanDump(sig, sig, args);
        var in = CalldataInput.parse(dump);
        return Arguments.of(label, sig, in);
    }

    /**
     * Builds a calldata body for a single dynamic top-level argument. The body is: a 32-byte offset
     * word (value = 32) followed by the argument's ABI payload.
     */
    private static byte[] singleDynBody(Val.Dynamic val) {
        var payload = val.payload();
        var body = new byte[32 + payload.length];
        body[31] = 32; // offset = 32
        System.arraycopy(payload, 0, body, 32, payload.length);
        return body;
    }

    /**
     * Heuristic round-trip: the {@link CalldataInput} is constructed directly with {@code
     * topLevelTypes = null} so the decoder uses pure heuristics. The method name is provided
     * explicitly so the emitted YAML can contain the correct {@code method_names} entry.
     *
     * @param label
     *            display name for the test case
     * @param methodName
     *            method name to embed in the emitted YAML
     * @param sig
     *            full canonical signature (used to compute the selector)
     * @param body
     *            pre-built calldata body (without the 4-byte selector)
     */
    private static Arguments heuristicCase(String label, String methodName, String sig,
        byte[] body) {
        var selector = Arrays.copyOf(Keccak256Util.hash(sig), 4);
        var in = new CalldataInput(selector, body, methodName, null);
        return Arguments.of(label, sig, in);
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void roundTripDecodesAndSearchFindsSignature(String label, String sig, CalldataInput in) {
        var decodedArgs = AbiDecoder.decodeArgs(in.body(), in.topLevelTypes());
        var yaml = ConfigEmitter.emit(in.selector(), in.methodName(), decodedArgs);

        var cfg = new YamlConfigParser()
            .parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        var results = new SearchEngine(cfg,
            new PrintStream(OutputStream.nullOutputStream())).search();

        assertTrue(results.contains(sig), "Expected '" + sig + "' in search results for case ["
            + label + "]." + "\nEmitted YAML:\n" + yaml + "\nActual results: " + results);
    }
}
