package dev.aomarai.minecraftmcp.rcon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RconConfigTest {

    private static String message(Runnable action) {
        return assertThrows(IllegalArgumentException.class, action::run).getMessage();
    }

    @Test
    void aLoopbackHostNeedsNoPermissionAndKeepsItsValues() {
        RconConfig config = new RconConfig("127.0.0.1", 25575, "secret", false);

        assertEquals("127.0.0.1", config.host());
        assertEquals(25575, config.port());
        assertEquals("secret", config.password());
        assertFalse(config.allowRemote());
    }

    @Test
    void localhostAndTheIpv6LoopbackCountAsThisMachine() {
        assertDoesNotThrow(() -> new RconConfig("localhost", 25575, "secret", false));
        assertDoesNotThrow(() -> new RconConfig("::1", 25575, "secret", false));
    }

    @Test
    void aRemoteHostIsRefusedUnlessAllowedOnPurpose() {
        assertEquals("host '192.0.2.10' is not this machine; RCON is not encrypted, so a remote host needs "
                        + "allowRemote to be set explicitly",
                message(() -> new RconConfig("192.0.2.10", 25575, "secret", false)));

        RconConfig allowed = new RconConfig("192.0.2.10", 25575, "secret", true);
        assertTrue(allowed.allowRemote());
        assertEquals("192.0.2.10", allowed.host());
    }

    @Test
    void anAllowedRemoteHostIsNotLookedUp() {
        assertDoesNotThrow(() -> new RconConfig("no-such-host.invalid", 25575, "secret", true));
    }

    @Test
    void aHostThatCannotBeResolvedIsRefusedByName() {
        assertEquals("host 'no-such-host.invalid' cannot be resolved",
                message(() -> new RconConfig("no-such-host.invalid", 25575, "secret", false)));
    }

    @Test
    void theHostMustBeGiven() {
        assertEquals("host must not be empty", message(() -> new RconConfig(null, 25575, "secret", false)));
        assertEquals("host must not be empty", message(() -> new RconConfig("", 25575, "secret", false)));
        assertEquals("host must not be empty", message(() -> new RconConfig("  ", 25575, "secret", false)));
    }

    @Test
    void thePortIsBounded() {
        assertEquals("port must be between 1 and 65535", message(() -> new RconConfig("127.0.0.1", 0, "secret", false)));
        assertEquals("port must be between 1 and 65535", message(() -> new RconConfig("127.0.0.1", 65536, "secret", false)));
        assertEquals(1, new RconConfig("127.0.0.1", 1, "secret", false).port());
        assertEquals(65535, new RconConfig("127.0.0.1", 65535, "secret", false).port());
    }

    @Test
    void thePasswordIsRequired() {
        String expected = "password must not be empty (the game requires rcon.password)";
        assertEquals(expected, message(() -> new RconConfig("127.0.0.1", 25575, null, false)));
        assertEquals(expected, message(() -> new RconConfig("127.0.0.1", 25575, "", false)));
        assertDoesNotThrow(() -> new RconConfig("127.0.0.1", 25575, " ", false));
    }

    @Test
    void thePasswordIsNeverPrinted() {
        String text = new RconConfig("127.0.0.1", 25575, "hunter2-hunter2", false).toString();

        assertEquals("RconConfig[host=127.0.0.1, port=25575, password=<hidden>, allowRemote=false]", text);
        assertFalse(text.contains("hunter2"));
    }
}
