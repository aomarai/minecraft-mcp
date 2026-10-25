package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private static McpConfig config(int port, int maxVolume, Duration timeout) {
        return new McpConfig("localhost", port, false, false, maxVolume, timeout);
    }

    @Test
    void defaultsAreSecureAndLoopbackBound() {
        McpConfig defaults = McpConfig.defaults();
        assertEquals("127.0.0.1", defaults.host());
        assertEquals(25599, defaults.port());
        assertFalse(defaults.allowWrites());
        assertFalse(defaults.allowAdmin());
        assertEquals(32768, defaults.maxBlockVolume());
        assertEquals(Duration.ofSeconds(8), defaults.callTimeout());
    }

    @Test
    void commandListsDefaultToEmptyAndAreNormalized() {
        assertEquals(java.util.Set.of(), McpConfig.defaults().allowedCommands());
        assertEquals(java.util.Set.of(), McpConfig.defaults().destructiveCommands());
        McpConfig config = new McpConfig("h", 1, false, false, 1, ONE_SECOND,
                java.util.Set.of(" MyMod:Status ", "Minecraft:Say"), java.util.Set.of("MINECRAFT:KILL"));
        assertEquals(java.util.Set.of("mymod:status", "say"), config.allowedCommands());
        assertEquals(java.util.Set.of("kill"), config.destructiveCommands());
    }

    @Test
    void nullCommandListsBecomeEmpty() {
        McpConfig config = new McpConfig("h", 1, false, false, 1, ONE_SECOND, null, null);
        assertEquals(java.util.Set.of(), config.allowedCommands());
        assertEquals(java.util.Set.of(), config.destructiveCommands());
    }

    @Test
    void commandNamesMustBeSingleWords() {
        for (String bad : new String[]{"", "  ", "two words", "/slash", "tab\there", "minecraft:"}) {
            IllegalArgumentException a = assertThrows(IllegalArgumentException.class, () ->
                    new McpConfig("h", 1, false, false, 1, ONE_SECOND, java.util.Set.of(bad), java.util.Set.of()));
            assertTrue(a.getMessage().startsWith("allowedCommands must be single command names"), a.getMessage());
            IllegalArgumentException d = assertThrows(IllegalArgumentException.class, () ->
                    new McpConfig("h", 1, false, false, 1, ONE_SECOND, java.util.Set.of(), java.util.Set.of(bad)));
            assertTrue(d.getMessage().startsWith("destructiveCommands must be single command names"), d.getMessage());
        }
    }

    @Test
    void nullHostFallsBackToLoopback() {
        assertEquals("127.0.0.1", new McpConfig(null, 1, false, false, 1, ONE_SECOND).host());
    }

    @Test
    void explicitHostIsKept() {
        assertEquals("localhost", config(1, 1, ONE_SECOND).host());
    }

    @Test
    void nullTimeoutFallsBackToDefault() {
        assertEquals(Duration.ofSeconds(8), config(1, 1, null).callTimeout());
    }

    @Test
    void flagsArePreserved() {
        McpConfig both = new McpConfig("h", 1, true, true, 1, ONE_SECOND);
        assertTrue(both.allowWrites());
        assertTrue(both.allowAdmin());
        McpConfig adminOnly = new McpConfig("h", 1, false, true, 1, ONE_SECOND);
        assertFalse(adminOnly.allowWrites());
        assertTrue(adminOnly.allowAdmin());
        McpConfig writesOnly = new McpConfig("h", 1, true, false, 1, ONE_SECOND);
        assertTrue(writesOnly.allowWrites());
        assertFalse(writesOnly.allowAdmin());
    }

    @Test
    void portBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> config(0, 1, ONE_SECOND));
        assertThrows(IllegalArgumentException.class, () -> config(65536, 1, ONE_SECOND));
        assertEquals(1, config(1, 1, ONE_SECOND).port());
        assertEquals(65535, config(65535, 1, ONE_SECOND).port());
    }

    @Test
    void maxBlockVolumeBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> config(1, 0, ONE_SECOND));
        assertThrows(IllegalArgumentException.class, () -> config(1, 1_000_001, ONE_SECOND));
        assertEquals(1, config(1, 1, ONE_SECOND).maxBlockVolume());
        assertEquals(1_000_000, config(1, 1_000_000, ONE_SECOND).maxBlockVolume());
    }

    @Test
    void timeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> config(1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> config(1, 1, Duration.ofMillis(-1)));
        assertEquals(Duration.ofNanos(1), config(1, 1, Duration.ofNanos(1)).callTimeout());
    }
}
