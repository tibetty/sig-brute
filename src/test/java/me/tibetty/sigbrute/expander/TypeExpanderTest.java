package me.tibetty.sigbrute.expander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TypeExpanderTest {

    @Test
    void normalizeFixedAliases() {
        assertEquals(List.of("fixed128x18"), TypeExpander.expand("fixed"));
        assertEquals(List.of("ufixed128x18"), TypeExpander.expand("ufixed"));
        assertEquals(List.of("fixed128x18"), TypeExpander.expand("fixed128x18"));
    }

    @Test
    void expandFixedMxNWildcard() {
        var out = TypeExpander.expand("fixed*x18");
        assertEquals(32, out.size());
        assertEquals("fixed8x18", out.get(0));
        assertEquals("fixed256x18", out.get(out.size() - 1));
    }

    @Test
    void expandFixedMWildcard() {
        var out = TypeExpander.expand("fixed128x*");
        assertEquals(80, out.size());
        assertEquals("fixed128x1", out.get(0));
        assertEquals("fixed128x80", out.get(out.size() - 1));
    }

    @Test
    void expandFixedFullWildcard() {
        var out = TypeExpander.expand("fixed*");
        assertEquals(32 * 80, out.size());
        assertTrue(out.contains("fixed8x1"));
        assertTrue(out.contains("fixed256x80"));
    }

    @Test
    void expandUfixedWithArraySuffix() {
        var out = TypeExpander.expand("ufixed*x18[]");
        assertEquals(32, out.size());
        assertEquals("ufixed128x18[]", out.get(15));
    }

    @Test
    void expandFixedWithArraySuffix() {
        var out = TypeExpander.expand("fixed*[]");
        assertEquals(32 * 80, out.size());
        assertTrue(out.contains("fixed128x18[]"));
    }

    @Test
    void rejectsInvalidFixedPattern() {
        assertThrows(IllegalArgumentException.class, () -> TypeExpander.expand("fixed*x0"));
    }
}
