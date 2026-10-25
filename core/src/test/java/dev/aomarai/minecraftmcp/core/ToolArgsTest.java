package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolArgsTest {

    private static ToolArgs args(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return new ToolArgs(map);
    }

    private static String failure(Runnable action) {
        return assertThrows(IllegalArgumentException.class, action::run).getMessage();
    }

    @Test
    void stringsMustBeStrings() {
        assertEquals("v", args("a", "v").string("a"));
        assertEquals("a must be a string", failure(() -> args("a", 5).string("a")));
        assertEquals("a must be a string", failure(() -> args().string("a")));
        assertEquals("a must be a string", failure(() -> args("a", null).string("a")));
    }

    @Test
    void optionalStringsFallBackOnlyWhenAbsent() {
        assertEquals("fallback", args().optionalString("a", "fallback"));
        assertEquals("fallback", args("a", null).optionalString("a", "fallback"));
        assertEquals("given", args("a", "given").optionalString("a", "fallback"));
        assertEquals("a must be a string", failure(() -> args("a", 1).optionalString("a", "fallback")));
    }

    @Test
    void integersMustBeExactAndFitAnInt() {
        assertEquals(5, args("a", 5).integer("a"));
        assertEquals(5, args("a", 5L).integer("a"));
        assertEquals(5, args("a", 5.0).integer("a"));
        assertEquals(-7, args("a", -7).integer("a"));
        for (Object bad : new Object[]{1.5, "5", true, 3_000_000_000L, -3_000_000_000L}) {
            assertEquals("a must be an integer", failure(() -> args("a", bad).integer("a")), String.valueOf(bad));
        }
        assertEquals("a must be an integer", failure(() -> args().integer("a")));
        assertEquals(Integer.MAX_VALUE, args("a", Integer.MAX_VALUE).integer("a"));
        assertEquals(Integer.MIN_VALUE, args("a", (long) Integer.MIN_VALUE).integer("a"));
    }

    @Test
    void optionalIntegersFallBackOnlyWhenAbsent() {
        assertEquals(9, args().optionalInteger("a", 9));
        assertEquals(9, args("a", null).optionalInteger("a", 9));
        assertEquals(3, args("a", 3).optionalInteger("a", 9));
        assertEquals("a must be an integer", failure(() -> args("a", "3").optionalInteger("a", 9)));
    }

    @Test
    void optionalLongsFallBackOnlyWhenAbsentAndKeepTheirWholeRange() {
        assertEquals(-1L, args().optionalLong("a", -1));
        assertEquals(-1L, args("a", null).optionalLong("a", -1));
        assertEquals(5_000_000_000L, args("a", 5_000_000_000L).optionalLong("a", -1));
        assertEquals(0L, args("a", 0).optionalLong("a", -1));
        assertEquals("a must be an integer", failure(() -> args("a", "3").optionalLong("a", -1)));
        assertEquals("a must be an integer", failure(() -> args("a", 1.5).optionalLong("a", -1)));
    }

    @Test
    void optionalBooleansFallBackOnlyWhenAbsent() {
        assertTrue(args().optionalBoolean("a", true));
        assertFalse(args().optionalBoolean("a", false));
        assertFalse(args("a", null).optionalBoolean("a", false));
        assertTrue(args("a", true).optionalBoolean("a", false));
        assertFalse(args("a", false).optionalBoolean("a", true));
        for (Object bad : new Object[]{"true", 1, 0}) {
            assertEquals("a must be true or false", failure(() -> args("a", bad).optionalBoolean("a", false)), String.valueOf(bad));
        }
    }

    @Test
    void numbersMustBeFiniteNumbers() {
        assertEquals(1.5, args("a", 1.5).number("a"));
        assertEquals(2.0, args("a", 2).number("a"));
        assertEquals(-0.25, args("a", -0.25f).number("a"));
        for (Object bad : new Object[]{"1", true, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals("a must be a number", failure(() -> args("a", bad).number("a")), String.valueOf(bad));
        }
        assertEquals("a must be a number", failure(() -> args().number("a")));
    }
}
