package dev.aomarai.minecraftmcp.rcon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class UniversalConfigTest {

    private static final RconConfig RCON = new RconConfig("127.0.0.1", 25575, "a password", false);

    @Test
    void theOptionalPartsAreKept() {
        UniversalConfig config = new UniversalConfig(RCON, OptionalInt.of(25565), Optional.of(Path.of("server")));

        assertSame(RCON, config.rcon());
        assertEquals(25565, config.queryPort().getAsInt());
        assertEquals(Path.of("server"), config.serverDirectory().orElseThrow());
    }

    @Test
    void everythingButRconMayBeLeftOut() {
        UniversalConfig config = new UniversalConfig(RCON, OptionalInt.empty(), Optional.empty());

        assertTrue(config.queryPort().isEmpty());
        assertTrue(config.serverDirectory().isEmpty());
    }

    @Test
    void theQueryPortIsBounded() {
        assertEquals("queryPort must be between 1 and 65535", assertThrows(IllegalArgumentException.class,
                () -> new UniversalConfig(RCON, OptionalInt.of(0), Optional.empty())).getMessage());
        assertEquals("queryPort must be between 1 and 65535", assertThrows(IllegalArgumentException.class,
                () -> new UniversalConfig(RCON, OptionalInt.of(65536), Optional.empty())).getMessage());
        assertDoesNotThrow(() -> new UniversalConfig(RCON, OptionalInt.of(1), Optional.empty()));
        assertDoesNotThrow(() -> new UniversalConfig(RCON, OptionalInt.of(65535), Optional.empty()));
    }

    @Test
    void missingPartsAreNamed() {
        assertEquals("rcon", assertThrows(NullPointerException.class,
                () -> new UniversalConfig(null, OptionalInt.empty(), Optional.empty())).getMessage());
        assertEquals("queryPort", assertThrows(NullPointerException.class,
                () -> new UniversalConfig(RCON, null, Optional.empty())).getMessage());
        assertEquals("serverDirectory", assertThrows(NullPointerException.class,
                () -> new UniversalConfig(RCON, OptionalInt.empty(), null)).getMessage());
    }
}
