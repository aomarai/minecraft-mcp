package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInfoTest {

    @Test
    void exposesAllComponents() {
        PlayerInfo info = new PlayerInfo("Steve", "uuid-1", "minecraft:the_nether", 1.5, 64.0, -2.5, true);
        assertEquals("Steve", info.name());
        assertEquals("uuid-1", info.uuid());
        assertEquals("minecraft:the_nether", info.dimension());
        assertEquals(1.5, info.x());
        assertEquals(64.0, info.y());
        assertEquals(-2.5, info.z());
        assertTrue(info.op());
    }
}
