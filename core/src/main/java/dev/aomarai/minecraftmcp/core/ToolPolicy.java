package dev.aomarai.minecraftmcp.core;

import java.util.Locale;
import java.util.Set;

/**
 * Default-deny policy for commands and tool scopes.
 *
 * <p>Read-only by default. Destructive commands require both admin access and an
 * explicit confirmation flag from the caller. This is intentionally independent
 * of Minecraft's own permission system: an AI must never inherit OP just because
 * the server operator has it.</p>
 */
public final class ToolPolicy {

    public enum Scope {
        OBSERVE,
        ACT,
        ADMIN
    }

    private static final Set<String> DESTRUCTIVE_ROOTS = Set.of(
            "kill", "ban", "ban-ip", "pardon", "op", "deop", "stop", "whitelist",
            "fill", "clone", "setblock", "summon", "tp", "teleport", "spreadplayers",
            "save-off", "save-all", "reload", "kick", "difficulty", "gamerule");

    private final boolean allowWrites;
    private final boolean allowAdmin;

    public ToolPolicy(boolean allowWrites, boolean allowAdmin) {
        this.allowWrites = allowWrites;
        this.allowAdmin = allowAdmin;
    }

    public Decision checkCommand(String rawCommand, boolean confirmed) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.isEmpty()) {
            return Decision.deny("empty command");
        }
        if (DESTRUCTIVE_ROOTS.contains(root)) {
            if (!allowAdmin) {
                return Decision.deny("admin commands are disabled");
            }
            return confirmed
                    ? Decision.allow()
                    : Decision.confirm("destructive command requires confirmation");
        }
        if (!allowWrites) {
            return Decision.deny("write commands are disabled");
        }
        return Decision.allow();
    }

    public Decision checkScope(Scope scope, boolean confirmed) {
        return switch (scope) {
            case OBSERVE -> Decision.allow();
            case ACT -> allowWrites ? Decision.allow() : Decision.deny("write access is disabled");
            case ADMIN -> {
                if (!allowAdmin) {
                    yield Decision.deny("admin access is disabled");
                }
                yield confirmed ? Decision.allow() : Decision.confirm("admin action requires confirmation");
            }
        };
    }

    public record Decision(boolean allowed, boolean requiresConfirmation, String reason) {
        static Decision allow() {
            return new Decision(true, false, "");
        }

        static Decision deny(String reason) {
            return new Decision(false, false, reason);
        }

        static Decision confirm(String reason) {
            return new Decision(true, true, reason);
        }
    }
}
