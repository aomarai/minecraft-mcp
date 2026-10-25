package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ToolPolicyTest {

    private static void assertDenied(ToolPolicy.Decision decision, String reason) {
        assertFalse(decision.allowed());
        assertFalse(decision.requiresConfirmation());
        assertEquals(reason, decision.reason());
    }

    private static void assertAllowed(ToolPolicy.Decision decision) {
        assertTrue(decision.allowed());
        assertFalse(decision.requiresConfirmation());
        assertEquals("", decision.reason());
    }

    @Test
    void deniesWritesByDefault() {
        ToolPolicy policy = new ToolPolicy(false, false);
        assertDenied(policy.checkCommand("say hi", false), "write commands are disabled");
        assertDenied(policy.checkScope(ToolPolicy.Scope.ACT, false), "write access is disabled");
    }

    @Test
    void allowsNonDestructiveCommandsWhenWritesEnabled() {
        assertAllowed(new ToolPolicy(true, false).checkCommand("say hi", false));
    }

    @Test
    void destructiveCommandsNeedAdminEvenWhenWritesEnabled() {
        assertDenied(new ToolPolicy(true, false).checkCommand("op Steve", true), "admin commands are disabled");
        assertDenied(new ToolPolicy(false, false).checkCommand("op Steve", true), "admin commands are disabled");
    }

    @Test
    void adminWithoutWritesStillGatesDestructiveCommandsByConfirmation() {
        ToolPolicy policy = new ToolPolicy(false, true);
        assertTrue(policy.checkCommand("kill @e", false).requiresConfirmation());
        assertAllowed(policy.checkCommand("kill @e", true));
        assertDenied(policy.checkCommand("say hi", false), "write commands are disabled");
    }

    @Test
    void requiresConfirmationForDestructiveCommands() {
        ToolPolicy policy = new ToolPolicy(true, true);
        ToolPolicy.Decision decision = policy.checkCommand("/op Steve", false);
        assertTrue(decision.allowed());
        assertTrue(decision.requiresConfirmation());
        assertEquals("destructive command requires confirmation", decision.reason());
        assertAllowed(policy.checkCommand("op Steve", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kill", "ban", "ban-ip", "pardon", "op", "deop", "stop", "whitelist",
            "fill", "clone", "setblock", "summon", "tp", "teleport", "spreadplayers",
            "save-off", "save-all", "reload", "kick", "difficulty", "gamerule"})
    void everyDestructiveRootIsGated(String root) {
        ToolPolicy policy = new ToolPolicy(true, true);
        assertTrue(policy.checkCommand(root + " arg", false).requiresConfirmation(), root);
        assertTrue(policy.checkCommand(root, false).requiresConfirmation(), root);
        assertDenied(new ToolPolicy(true, false).checkCommand(root, true), "admin commands are disabled");
    }

    @Test
    void rootMatchingIsCaseInsensitiveAndWhitespaceTolerant() {
        ToolPolicy policy = new ToolPolicy(true, true);
        assertTrue(policy.checkCommand("  /OP   Steve  ", false).requiresConfirmation());
        assertTrue(policy.checkCommand("Kill\t@e", false).requiresConfirmation());
    }

    @Test
    void onlyOneLeadingSlashIsStripped() {
        // "//op" has root "/op", which is not a destructive root.
        assertAllowed(new ToolPolicy(true, true).checkCommand("//op", false));
    }

    @Test
    void rootMustMatchExactlyNotByPrefix() {
        assertAllowed(new ToolPolicy(true, false).checkCommand("opx", false));
        assertAllowed(new ToolPolicy(true, false).checkCommand("tpx 1 2 3", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "/", "/  "})
    void emptyCommandsAreDeniedEvenWithFullPrivileges(String raw) {
        assertDenied(new ToolPolicy(true, true).checkCommand(raw, true), "empty command");
    }

    @Test
    void nullCommandIsDenied() {
        assertDenied(new ToolPolicy(true, true).checkCommand(null, true), "empty command");
    }

    @Test
    void observeIsAlwaysAllowed() {
        assertAllowed(new ToolPolicy(false, false).checkScope(ToolPolicy.Scope.OBSERVE, false));
    }

    @Test
    void actRequiresWrites() {
        assertAllowed(new ToolPolicy(true, false).checkScope(ToolPolicy.Scope.ACT, false));
        assertDenied(new ToolPolicy(false, true).checkScope(ToolPolicy.Scope.ACT, true), "write access is disabled");
    }

    @Test
    void adminScopeRequiresAdminThenConfirmation() {
        assertDenied(new ToolPolicy(true, false).checkScope(ToolPolicy.Scope.ADMIN, true), "admin access is disabled");
        ToolPolicy policy = new ToolPolicy(false, true);
        ToolPolicy.Decision pending = policy.checkScope(ToolPolicy.Scope.ADMIN, false);
        assertTrue(pending.allowed());
        assertTrue(pending.requiresConfirmation());
        assertEquals("admin action requires confirmation", pending.reason());
        assertAllowed(policy.checkScope(ToolPolicy.Scope.ADMIN, true));
    }
}
