package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolPolicyTest {

    @Test
    void deniesWritesByDefault() {
        ToolPolicy policy = new ToolPolicy(false, false);
        assertFalse(policy.checkCommand("setblock 0 64 0 stone", false).allowed());
        assertFalse(policy.checkScope(ToolPolicy.Scope.ACT, false).allowed());
    }

    @Test
    void requiresConfirmationForDestructiveCommands() {
        ToolPolicy policy = new ToolPolicy(true, true);
        ToolPolicy.Decision decision = policy.checkCommand("/op Steve", false);
        assertTrue(decision.allowed());
        assertTrue(decision.requiresConfirmation());
        assertTrue(policy.checkCommand("op Steve", true).allowed());
        assertFalse(policy.checkCommand("op Steve", true).requiresConfirmation());
    }

    @Test
    void observeIsAlwaysAllowed() {
        assertTrue(new ToolPolicy(false, false).checkScope(ToolPolicy.Scope.OBSERVE, false).allowed());
    }

    @Test
    void computesBlockQueryVolume() {
        BlockQuery query = new BlockQuery("minecraft:overworld", 0, 0, 0, 1, 1, 1, 8);
        assertEquals(8, query.volume());
    }
}
