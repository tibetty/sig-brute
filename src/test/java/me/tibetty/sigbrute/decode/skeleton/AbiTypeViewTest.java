package me.tibetty.sigbrute.decode.skeleton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AbiTypeViewTest {

    @Test
    void of_inlineTupleDoubleArray() {
        var view = AbiTypeView.of("(uint8,address)[][]");
        assertEquals("(uint8,address)", view.base());
        assertEquals("[][]", view.arraySuffix());
        assertEquals(2, view.dynamicDimensionCount());
        assertTrue(view.isInlineTupleBase());
    }

    @Test
    void peelOneDimension_stepsInward() {
        var view = AbiTypeView.of("(uint8,address)[][]");
        var once = view.peelOneDimension();
        assertNotNull(once);
        assertEquals("(uint8,address)[]", once.raw());
        var twice = once.peelOneDimension();
        assertNotNull(twice);
        assertEquals("(uint8,address)", twice.base());
        assertEquals("", twice.arraySuffix());
        assertNull(twice.peelOneDimension());
    }

    @Test
    void of_staticInlineTuple_hasNoSuffix() {
        var view = AbiTypeView.of("(uint256,address)");
        assertEquals("(uint256,address)", view.base());
        assertEquals("", view.arraySuffix());
        assertEquals(0, view.dynamicDimensionCount());
    }
}
