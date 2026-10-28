package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

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
        // "//op" has root "/op", which is nothing the policy recognises, so it is not a plain write.
        assertTrue(new ToolPolicy(true, true).checkCommand("//op", false).requiresConfirmation());
    }

    @Test
    void rootMustMatchExactlyNotByPrefix() {
        // "opx" and "tpx" are not "op" and "tp"; they are unrecognised, and unrecognised needs admin.
        assertDenied(new ToolPolicy(true, false).checkCommand("opx", false),
                "unrecognised command 'opx' needs admin access; list it in allowedCommands to trust it");
        assertEquals("unrecognised command 'tpx' requires confirmation",
                new ToolPolicy(true, true).checkCommand("tpx 1 2 3", false).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"say", "tell", "msg", "w", "me", "list", "seed", "help", "time", "weather"})
    void everyKnownSafeCommandIsAPlainWrite(String root) {
        assertAllowed(new ToolPolicy(true, false).checkCommand(root + " x", false));
        assertDenied(new ToolPolicy(false, true).checkCommand(root + " x", false), "write commands are disabled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"say", "tell", "msg", "w", "me"})
    void messageCommandsWithAnEntitySelectorNeedAdminAndConfirmation(String root) {
        for (String selector : new String[]{"@a", "@e", "@p", "@r", "@s", "@n"}) {
            String inMessage = root + " Steve hi " + selector;
            String asTarget = root + " " + selector + " hi";
            ToolPolicy writes = new ToolPolicy(true, false);
            String denied = "'" + root + "' with an entity selector (@a, @e, ...) needs admin access; "
                    + "name the player or use send_message";
            assertDenied(writes.checkCommand(inMessage, true), denied);
            assertDenied(writes.checkCommand(asTarget, true), denied);

            ToolPolicy admin = new ToolPolicy(true, true);
            ToolPolicy.Decision ask = admin.checkCommand(asTarget, false);
            assertTrue(ask.allowed());
            assertTrue(ask.requiresConfirmation());
            assertEquals("'" + root + "' with an entity selector requires confirmation", ask.reason());
            assertAllowed(admin.checkCommand(asTarget, true));
        }
    }

    @Test
    void theSelectorCheckSeesThroughASlashAndTheVanillaNamespace() {
        ToolPolicy writes = new ToolPolicy(true, false);
        assertFalse(writes.checkCommand("/say @e @e @e", false).allowed());
        assertFalse(writes.checkCommand("minecraft:msg @a hi", false).allowed());
        assertEquals("'say' with an entity selector (@a, @e, ...) needs admin access; name the player or use send_message",
                writes.checkCommand("MINECRAFT:SAY hi @e", false).reason());
    }

    @Test
    void messagesWithoutASelectorStayPlainWrites() {
        ToolPolicy writes = new ToolPolicy(true, false);
        assertAllowed(writes.checkCommand("say hello", false));
        assertAllowed(writes.checkCommand("tell Steve hi", false));
        assertAllowed(writes.checkCommand("msg Steve write to a@b.c or @ home", false));
        assertDenied(new ToolPolicy(false, false).checkCommand("tell Steve hi", false), "write commands are disabled");
    }

    @Test
    void onlyTheMessageCommandsAreCheckedForSelectors() {
        assertAllowed(new ToolPolicy(true, false).checkCommand("list @a", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"effect give @a minecraft:instant_damage 1 255", "xp add @a -1000 levels",
            "experience set @a 0", "give @a minecraft:stone 2147483647", "particle minecraft:flame ~ ~ ~ 1 1 1 1 99999",
            "playsound minecraft:entity.generic.explode master @a", "title @a title {\"text\":\"x\"}",
            "tellraw @a {\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/stop\"}}"})
    void commandsThatCanHarmOrFloodAreNotPlainWrites(String command) {
        String root = command.split(" ")[0];
        assertDenied(new ToolPolicy(true, false).checkCommand(command, true),
                "unrecognised command '" + root + "' needs admin access; list it in allowedCommands to trust it");
        assertEquals("unrecognised command '" + root + "' requires confirmation",
                new ToolPolicy(true, true).checkCommand(command, false).reason());
        assertAllowed(new ToolPolicy(true, true).checkCommand(command, true));
        assertAllowed(new ToolPolicy(true, false, java.util.Set.of(root), java.util.Set.of()).checkCommand(command, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"execute run say hi", "mymod:wipe_world", "function mypack:boom", "data merge", "frobnicate"})
    void unrecognisedCommandsAreTreatedLikeDestructiveOnes(String command) {
        String root = command.split(" ")[0];
        ToolPolicy.Decision disabled = new ToolPolicy(true, false).checkCommand(command, true);
        assertDenied(disabled, "unrecognised command '" + root + "' needs admin access; list it in allowedCommands to trust it");

        ToolPolicy admin = new ToolPolicy(true, true);
        ToolPolicy.Decision pending = admin.checkCommand(command, false);
        assertTrue(pending.allowed());
        assertTrue(pending.requiresConfirmation());
        assertEquals("unrecognised command '" + root + "' requires confirmation", pending.reason());
        assertAllowed(admin.checkCommand(command, true));
    }

    @Test
    void writesAloneNeverUnlockAnUnrecognisedCommand() {
        assertTrue(new ToolPolicy(true, false).checkCommand("mymod:wipe", true).reason().contains("needs admin access"));
        assertFalse(new ToolPolicy(false, false).checkCommand("mymod:wipe", true).allowed());
    }

    @Test
    void theVanillaNamespaceIsIgnoredSoAliasesCannotBypassThePolicy() {
        ToolPolicy admin = new ToolPolicy(true, true);
        assertEquals("destructive command requires confirmation", admin.checkCommand("minecraft:kill @e", false).reason());
        assertEquals("destructive command requires confirmation", admin.checkCommand("/MINECRAFT:Stop", false).reason());
        assertDenied(new ToolPolicy(true, false).checkCommand("minecraft:op Steve", true), "admin commands are disabled");
        assertAllowed(admin.checkCommand("minecraft:say hi", false));
    }

    @Test
    void otherNamespacesAreDifferentCommands() {
        // "other:kill" is not vanilla's kill, so it is not known safe, and not "kill" either.
        assertEquals("unrecognised command 'other:kill' requires confirmation",
                new ToolPolicy(true, true).checkCommand("other:kill", false).reason());
    }

    @Test
    void anOperatorCanTrustACommand() {
        ToolPolicy policy = new ToolPolicy(true, false, Set.of("mymod:status"), Set.of());
        assertAllowed(policy.checkCommand("mymod:status all", false));
        assertDenied(new ToolPolicy(false, false, Set.of("mymod:status"), Set.of()).checkCommand("mymod:status", false),
                "write commands are disabled");
        assertTrue(policy.checkCommand("mymod:other", false).reason().contains("needs admin access"));
    }

    @Test
    void anOperatorCanMarkACommandDestructive() {
        ToolPolicy policy = new ToolPolicy(true, true, Set.of(), Set.of("give"));
        assertEquals("destructive command requires confirmation", policy.checkCommand("give Steve diamond", false).reason());
        assertDenied(new ToolPolicy(true, false, Set.of(), Set.of("give")).checkCommand("give Steve diamond", true),
                "admin commands are disabled");
    }

    @Test
    void destructiveBeatsAllowedWhenBothAreListed() {
        ToolPolicy policy = new ToolPolicy(true, true, Set.of("mymod:wipe"), Set.of("mymod:wipe"));
        assertEquals("destructive command requires confirmation", policy.checkCommand("mymod:wipe", false).reason());
    }

    @Test
    void theBuiltInDestructiveListCannotBeLoosenedByAllowing() {
        ToolPolicy policy = new ToolPolicy(true, true, Set.of("kill", "op"), Set.of());
        assertEquals("destructive command requires confirmation", policy.checkCommand("kill @e", false).reason());
        assertEquals("destructive command requires confirmation", policy.checkCommand("op Steve", false).reason());
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
