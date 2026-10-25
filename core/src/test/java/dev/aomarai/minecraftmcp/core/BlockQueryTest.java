package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockQueryTest {

    private static BlockQuery query(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int limit) {
        return new BlockQuery("minecraft:overworld", minX, minY, minZ, maxX, maxY, maxZ, limit);
    }

    @Test
    void volumeIsInclusiveOnEveryAxis() {
        assertEquals(1, query(5, 5, 5, 5, 5, 5, 1).volume());
        assertEquals(2 * 3 * 4, query(0, 0, 0, 1, 2, 3, 1).volume());
        assertEquals(3 * 5 * 7, query(-1, 10, -3, 1, 14, 3, 1).volume());
    }

    @Test
    void volumeDoesNotOverflowInt() {
        assertEquals(3_000_000L * 3_000_000L * 3_000_000L,
                query(0, 0, 0, 2_999_999, 2_999_999, 2_999_999, 1).volume());
    }

    @Test
    void rejectsNullDimension() {
        assertThrows(NullPointerException.class, () -> new BlockQuery(null, 0, 0, 0, 0, 0, 0, 1));
    }

    @Test
    void rejectsMaxBelowMinOnEachAxis() {
        assertThrows(IllegalArgumentException.class, () -> query(1, 0, 0, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> query(0, 1, 0, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> query(0, 0, 1, 0, 0, 0, 1));
    }

    @Test
    void acceptsEqualMinAndMax() {
        assertDoesNotThrow(() -> query(0, 0, 0, 0, 0, 0, 1));
    }

    @Test
    void enforcesLimitBounds() {
        assertThrows(IllegalArgumentException.class, () -> query(0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> query(0, 0, 0, 0, 0, 0, 4097));
        assertDoesNotThrow(() -> query(0, 0, 0, 0, 0, 0, 1));
        assertDoesNotThrow(() -> query(0, 0, 0, 0, 0, 0, 4096));
    }
}
