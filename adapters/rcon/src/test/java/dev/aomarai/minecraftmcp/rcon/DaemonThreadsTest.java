package dev.aomarai.minecraftmcp.rcon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DaemonThreadsTest {

    @Test
    void aThreadIsNamedAndADaemonAndNotYetStarted() {
        Thread thread = DaemonThreads.named("minecraft-mcp-test").newThread(() -> { });

        assertEquals("minecraft-mcp-test", thread.getName());
        assertTrue(thread.isDaemon());
        assertFalse(thread.isAlive());
    }

    @Test
    void theThreadRunsTheTaskItWasGiven() throws InterruptedException {
        boolean[] ran = {false};
        Thread thread = DaemonThreads.named("minecraft-mcp-test").newThread(() -> ran[0] = true);

        thread.start();
        thread.join(10_000);

        assertTrue(ran[0]);
    }
}
