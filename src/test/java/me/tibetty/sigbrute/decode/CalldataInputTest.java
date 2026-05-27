package me.tibetty.sigbrute.decode;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CalldataInputTest {

    @Test
    void parseEtherscanStyleBlock() {
        var input = """
            Function: transfer(address to, uint256 amount)

            MethodID: 0xa9059cbb
            [0]:  000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48
            [1]:  00000000000000000000000000000000000000000000000000000000000003e8
            """;
        var in = CalldataInput.parse(input);

        assertEquals(4, in.selector().length);
        assertEquals((byte) 0xa9, in.selector()[0]);
        assertEquals((byte) 0xbb, in.selector()[3]);
        assertEquals(64, in.body().length);
        assertEquals("transfer", in.methodName());
        assertEquals(List.of("address", "uint256"), in.topLevelTypes());
    }

    @Test
    void withoutSkeleton_clearsMethodNameAndTopLevelTypes() {
        var input = """
            Function: transfer(address to, uint256 amount)

            MethodID: 0xa9059cbb
            [0]:  000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48
            [1]:  00000000000000000000000000000000000000000000000000000000000003e8
            """;
        var in = CalldataInput.parse(input).withoutSkeleton();
        assertEquals(64, in.body().length);
        assertNull(in.methodName());
        assertNull(in.topLevelTypes());
    }

    @Test
    void parseRawHex() {
        var hex = "0xa9059cbb"
            + "000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
            + "00000000000000000000000000000000000000000000000000000000000003e8";
        var in = CalldataInput.parse(hex);

        assertEquals(4, in.selector().length);
        assertEquals(64, in.body().length);
        assertNull(in.methodName());
        assertNull(in.topLevelTypes());
    }

    @Test
    void rawHexAcceptsWhitespace() {
        var hex = """
            a9059cbb
            000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48
            00000000000000000000000000000000000000000000000000000000000003e8
            """;
        var in = CalldataInput.parse(hex);
        assertEquals(4, in.selector().length);
        assertEquals(64, in.body().length);
    }

    @Test
    void rejectsTextWithoutHex() {
        assertThrows(IllegalArgumentException.class, () -> CalldataInput.parse("hello world"));
    }

    @Test
    void parsesInlineTupleTypesInFunctionHeader() {
        var input = """
            Function: forwardEth(bytes,(uint256,address))

            MethodID: 0xfcaabe3b
            [0]: 0000000000000000000000000000000000000000000000000000000000000060
            [1]: 000000000000000000000000000000000000000000000000000016bcc41e9000
            [2]: 00000000000000000000000082d9a407f99a95db4671e7021d625cbd0787a407
            """;
        var in = CalldataInput.parse(input);
        assertEquals("forwardEth", in.methodName());
        assertEquals(List.of("bytes", "(uint256,address)"), in.topLevelTypes());
    }

    @Test
    void splitsTupleTypeWithoutGreedyName() {
        // The "Function:" inner can include tuple placeholders and parameter names.
        var input = """
            Function: foo(uint256 a, tuple b, tuple[] c)

            MethodID: 0xdeadbeef
            [0]: 0000000000000000000000000000000000000000000000000000000000000001
            """;
        var in = CalldataInput.parse(input);
        assertEquals(List.of("uint256", "tuple", "tuple[]"), in.topLevelTypes());
    }
}
