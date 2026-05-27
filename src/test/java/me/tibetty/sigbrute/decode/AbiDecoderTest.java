package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.tibetty.sigbrute.decode.emit.ConfigEmitter;
import me.tibetty.sigbrute.parser.YamlConfigParser;
import org.junit.jupiter.api.Test;

class AbiDecoderTest {

    @Test
    void decodeErc20TransferWithSkeleton() {
        var input = """
            Function: transfer(address to, uint256 amount)

            MethodID: 0xa9059cbb
            [0]: 000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48
            [1]: 00000000000000000000000000000000000000000000000000000000000003e8
            """;
        var in = CalldataInput.parse(input);
        var args = AbiDecoder.decodeArgs(in.body(), in.topLevelTypes());

        assertEquals(2, args.size());
        assertInstanceOf(DecodedArg.Leaf.class, args.get(0));
        assertEquals(List.of("address"), ((DecodedArg.Leaf) args.get(0)).candidates());
        assertEquals(List.of("uint256"), ((DecodedArg.Leaf) args.get(1)).candidates());
    }

    @Test
    void decodeErc20TransferWithoutSkeleton() {
        // Pure heuristic: should still recover 2 fields, address + uint.
        // value 0x3e8 = 1000 occupies bytes 30-31 → firstNonZero=30 → minBits=16 → uint16+
        var hex = "0xa9059cbb"
            + "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
            + "00000000000000000000000000000000000000000000000000000000000003e8";
        var in = CalldataInput.parse(hex);
        var args = AbiDecoder.decodeArgs(in.body(), null);

        assertEquals(2, args.size());
        // address a0b86991... has 20 non-zero body bytes → entropy check fires →
        // address + uint160+ (uint160..uint256) kept to catch uint256-encoded addresses.
        assertEquals(List.of("address", "uint160+"), ((DecodedArg.Leaf) args.get(0)).candidates());
        // Narrowed: value needs 10 bits → minimum uint16; expands to uint16..uint256 (31 types)
        assertEquals(List.of("uint16+"), ((DecodedArg.Leaf) args.get(1)).candidates());
    }

    @Test
    void decodeDagSwapByOrderIdSkeletonStructure() throws IOException {
        Path fixture = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata");
        var text = Files.readString(fixture, StandardCharsets.UTF_8);
        var in = CalldataInput.parse(text);
        var args = AbiDecoder.decodeArgs(in.body(), in.topLevelTypes());

        // Top level: uint256 leaf, static tuple with 5 fields, tuple[] of 3 elements.
        assertEquals(3, args.size());

        assertInstanceOf(DecodedArg.Leaf.class, args.get(0));

        assertInstanceOf(DecodedArg.Tuple.class, args.get(1));
        DecodedArg.Tuple base = (DecodedArg.Tuple) args.get(1);
        assertEquals("", base.arraySuffix(), "baseRequest is a plain tuple, no array suffix");
        assertEquals(5, base.fields().size(), "baseRequest should resolve to 5 static fields");

        assertInstanceOf(DecodedArg.Tuple.class, args.get(2));
        DecodedArg.Tuple paths = (DecodedArg.Tuple) args.get(2);
        assertEquals("[]", paths.arraySuffix(), "paths is a tuple[]");
        assertFalse(paths.fields().isEmpty(), "paths element should have decoded fields");
    }

    @Test
    void oversizedDynamicOffsetFallsBackToStaticDecode() {
        var body = oversizedOffsetCalldataBody();
        var decoded = AbiDecoder.decodeArgs(body, List.of("bytes"));
        assertEquals(1, decoded.size());
        assertInstanceOf(DecodedArg.Leaf.class, decoded.get(0));
    }

    private static byte[] oversizedOffsetCalldataBody() {
        var body = new byte[32];
        body[23] = 0x01; // bitLength=57 — exceeds isPlausibleOffset int guard
        return body;
    }

    @Test
    void freeFormPathOversizedOffsetIsIgnoredAsOffset() {
        var body = new byte[64];
        body[24] = 0x01; // slot 0: value = 1L << 56; bitLength() = 57 — exceeds isPlausibleOffset
        // guard
        body[63] = 0x01; // slot 1: value = 1 — small non-offset value
        var args = AbiDecoder.decodeArgs(body, null);
        assertEquals(2, args.size());
        assertInstanceOf(DecodedArg.Leaf.class, args.get(0));
        assertInstanceOf(DecodedArg.Leaf.class, args.get(1));
    }

    /**
     * A {@code bytes[]} field must be decoded as a {@code PrimArray("[]", [bytes, string])} rather
     * than a {@code Tuple("[]", ...)} with phantom tuple sub-fields.
     *
     * <p>
     * The dag_swap_by_order_id fixture has a {@code bytes[]} as field 3 of the first path
     * element; before the fix it was decoded as a dynamic-offset tuple array with 10 bogus
     * sub-fields because the bytes-element length word (0x140 / 0xc0) looks like an offset.
     */
    @Test
    void bytesArrayIsDecodedAsPrimArrayNotTuple() throws IOException {
        Path fixture = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata");
        var text = Files.readString(fixture, StandardCharsets.UTF_8);
        var in = CalldataInput.parse(text);
        var args = AbiDecoder.decodeArgs(in.body(), in.topLevelTypes());

        // arg[2] is the paths tuple[]; navigate to field 3 of the first element.
        DecodedArg.Tuple paths = (DecodedArg.Tuple) args.get(2);
        DecodedArg field3 = paths.fields().get(3);

        // Must be a PrimArray (bytes[]), NOT a Tuple with phantom sub-fields.
        assertInstanceOf(DecodedArg.PrimArray.class, field3,
            "field 3 should be bytes[] (PrimArray), not a Tuple — got: " + field3);
        DecodedArg.PrimArray bytesArr = (DecodedArg.PrimArray) field3;
        assertEquals("[]", bytesArr.arraySuffix());
        assertTrue(bytesArr.baseCandidates().contains("bytes"),
            "baseCandidates should contain 'bytes'");
    }

    /**
     * Array-element intersection must expand patterns before comparing strings. If element[0] →
     * ["uint184+","bytes32"] and element[1] → ["bytes32","uint256"], the intersection of the
     * expanded sets is {bytes32, uint256} — not just {bytes32}.
     */
    @Test
    void arrayElementIntersectionExpandsPatterns() throws IOException {
        Path fixture = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata");
        var text = Files.readString(fixture, StandardCharsets.UTF_8);
        var in = CalldataInput.parse(text);
        var args = AbiDecoder.decodeArgs(in.body(), in.topLevelTypes());

        // arg[2].field[2] is a uint256[] whose two elements span different inferred ranges.
        DecodedArg.Tuple paths = (DecodedArg.Tuple) args.get(2);
        DecodedArg field2 = paths.fields().get(2);

        assertInstanceOf(DecodedArg.PrimArray.class, field2,
            "field 2 should be a PrimArray — got: " + field2);
        DecodedArg.PrimArray uint256Arr = (DecodedArg.PrimArray) field2;
        // With expanded intersection, uint256 must appear as a candidate.
        assertTrue(uint256Arr.baseCandidates().contains("uint256"),
            "baseCandidates should contain 'uint256' after expanded intersection — got: "
                + uint256Arr.baseCandidates());
    }

    /**
     * The skeleton-typed primitive-array fix: {@code address[]} and {@code uint256[]} declared in
     * the Etherscan {@code Function:} line must not be heuristically re-identified as {@code bytes}
     * — a 1-element {@code address[]} encodes as count=1 + one 32-byte value, which is
     * byte-for-byte identical to {@code bytes} with a 1-byte payload.
     *
     * <p>
     * Real prototype: {@code
     * transferAndMulticall(address[],uint256[],(address,bool,uint256,bytes)[],address,address,bytes)}
     */
    @Test
    void decodeTransferAndMulticallSkeletonPrimitiveArrays() throws IOException {
        Path fixture = Path.of("src/main/resources/examples/calldata/transfer_and_multi_call.calldata");
        var text = Files.readString(fixture, StandardCharsets.UTF_8);
        var in = CalldataInput.parse(text);
        var args = AbiDecoder.decodeArgs(in.body(), in.topLevelTypes());

        // Skeleton: address[], uint256[], tuple[], address, address, bytes → 6 args
        assertEquals(6, args.size());

        // arg[0]: address[] — must be PrimArray([], [address]), NOT bytes/string
        assertInstanceOf(DecodedArg.PrimArray.class, args.get(0),
            "arg[0] should be PrimArray (address[]), not Leaf — got: " + args.get(0));
        DecodedArg.PrimArray tokens = (DecodedArg.PrimArray) args.get(0);
        assertEquals("[]", tokens.arraySuffix());
        assertEquals(List.of("address"), tokens.baseCandidates(),
            "address[] element type must be fixed to 'address' by skeleton");

        // arg[1]: uint256[] — must be PrimArray([], [uint256]), NOT bytes/string
        assertInstanceOf(DecodedArg.PrimArray.class, args.get(1),
            "arg[1] should be PrimArray (uint256[]), not Leaf — got: " + args.get(1));
        DecodedArg.PrimArray amounts = (DecodedArg.PrimArray) args.get(1);
        assertEquals("[]", amounts.arraySuffix());
        assertEquals(List.of("uint256"), amounts.baseCandidates(),
            "uint256[] element type must be fixed to 'uint256' by skeleton");

        // arg[2]: tuple[] with 4 fields; field 1 is bool false (all-zero) → bool must be a
        // candidate
        assertInstanceOf(DecodedArg.Tuple.class, args.get(2));
        DecodedArg.Tuple calls = (DecodedArg.Tuple) args.get(2);
        assertEquals("[]", calls.arraySuffix());
        assertEquals(4, calls.fields().size(), "tuple element should have 4 fields");
        DecodedArg field1 = calls.fields().get(1);
        assertInstanceOf(DecodedArg.Leaf.class, field1, "field 1 should be a Leaf");
        assertTrue(((DecodedArg.Leaf) field1).candidates().contains("bool"),
            "field 1 is bool false (all-zero) — 'bool' must be a candidate");
    }

    /**
     * Regression: a static tuple field whose {@code uint256} value (0xC0 = 192) coincidentally
     * satisfies all ABI-offset plausibility checks caused {@link AbiDecoder#scanHeadSize} to
     * return 192 instead of the correct 320 (= 10 × 32). The result was a 6-field outer tuple
     * with one deeply-nested "fallback dynamic tuple" instead of the correct 10-field flat tuple
     * with 4 dynamic tail fields (address[], address[], bytes, string).
     *
     * <p>Selector: {@code 0x0dc4bdae} — {@code exactInputV2Swap(tuple,uint256)}.
     */
    @Test
    void exactInputV2SwapFreeFormDecodeHasTenTupleFields() {
        var input = """
            MethodID: 0x0dc4bdae
            [0]:  0000000000000000000000000000000000000000000000000000000000000040
            [1]:  000000000000000000000000000000000000000000000000000000006a129229
            [2]:  000000000000000000000000f03bac88f177c943b7ede6fb95b8a626876a2f88
            [3]:  000000000000000000000000c02aaa39b223fe8d0a0e5c4f27ead9083c756cc2
            [4]:  0100000000000000000000007a250d5630b4cf539739df2c5dacb4c659f2488d
            [5]:  000000000000000000000000000000000000000000000000000000000000fa00
            [6]:  0000000000000000000000000000000000000000000000000000000005b61113
            [7]:  00000000000000000000000000000000000000000000000000000000000000c0
            [8]:  0000000000000000000000000000000000000000000000000000000000000140
            [9]:  00000000000000000000000000000000000000000000000000000000000001a0
            [10]: 00000000000000000000000000000000000000000000000000000000000001e0
            [11]: 0000000000000000000000000000000000000000000000000000000000000260
            [12]: 0000000000000000000000000000000000000000000000000000000000000002
            [13]: 000000000000000000000000dac17f958d2ee523a2206206994597c13d831ec7
            [14]: 00000000000000000000000075db45ad40dece3ff7e6a4c1ac941d43508b5b57
            [15]: 0000000000000000000000000000000000000000000000000000000000000001
            [16]: 000000000000000000000000fb3593a220d6647bdaa6115b13d21169e2c29adc
            [17]: 0000000000000000000000000000000000000000000000000000000000000041
            [18]: 9b4308e1f295724b3090c37ce6c77116f12a47312ccb053b0121d0342d43be8d
            [19]: 3dcea1d806b6d5372002f28a0ae7bb6736c263fc7ae61c3c8137051c145d1f89
            [20]: 1b00000000000000000000000000000000000000000000000000000000000000
            [21]: 0000000000000000000000000000000000000000000000000000000000000007
            [22]: 616e64726f696400000000000000000000000000000000000000000000000000
            """;
        var in = CalldataInput.parse(input);
        // Heuristic decode — no skeleton hint, pure byte-level inference.
        var args = AbiDecoder.decodeArgs(in.body(), null);

        // Top level: tuple at dynamic offset 64 + uint256 deadline.
        assertEquals(2, args.size());

        // arg[0] must be a Tuple decoded from the dynamic offset.
        assertInstanceOf(DecodedArg.Tuple.class, args.get(0),
            "arg[0] should be a dynamic tuple, got: " + args.get(0));
        var tuple = (DecodedArg.Tuple) args.get(0);

        // The inner tuple body has 10 ABI head fields:
        //   address, address, uint256, uint256, uint256, uint256 (static)
        //   + address[], address[], bytes, string (dynamic offsets).
        // Before the fix, scanHeadSize returned 192 (slot 5, value 0xC0 = 6×32) and
        // decoded only 6 fields with one deeply-nested fallback tuple.
        assertEquals(10, tuple.fields().size(),
            "inner tuple must have 10 fields (not 6 with a nested fallback) — fields: "
                + tuple.fields());

        // Field 6 is the 2-element address[] decoded as a PrimArray ("[]"), not a
        // nested Tuple — the wrong 6-field path wrapped the entire tail in one Tuple.
        DecodedArg field6 = tuple.fields().get(6);
        assertInstanceOf(DecodedArg.PrimArray.class, field6,
            "field[6] must be PrimArray (address[]), got: " + field6);
        assertEquals("[]", ((DecodedArg.PrimArray) field6).arraySuffix());
    }

    @Test
    void emittedYamlParsesAsSigBruteConfig() throws IOException {
        Path fixture = Path.of("src/main/resources/examples/calldata/dag_swap_by_order_id.calldata");
        var text = Files.readString(fixture, StandardCharsets.UTF_8);
        var in = CalldataInput.parse(text);
        var args = AbiDecoder.decodeArgs(in.body(), in.topLevelTypes());
        var yaml = ConfigEmitter.emit(in.selector(), in.methodName(), args);

        var cfg = new YamlConfigParser()
            .parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(4, cfg.selector().length);
        assertEquals((byte) 0xf2, cfg.selector()[0]);
        assertEquals((byte) 0x96, cfg.selector()[3]);
        assertEquals(List.of("dagSwapByOrderId"), cfg.methodNames());
        assertEquals(3, cfg.args().size());
    }
}
