package dev.aomarai.minecraftmcp.core;

import java.util.Set;

/**
 * Default-deny policy for commands and tool scopes.
 *
 * <p>Read-only by default. A command is one of three kinds, decided by its first word:</p>
 * <ul>
 *   <li><b>Known safe</b> (a small built-in list of everyday vanilla commands, plus the operator's
 *       {@code allowedCommands}): needs write access.</li>
 *   <li><b>Destructive</b> (a built-in list, plus the operator's {@code destructiveCommands}):
 *       needs admin access and an explicit confirmation.</li>
 *   <li><b>Anything else</b>: treated like a destructive command. A modded command, or a vanilla
 *       one such as {@code execute} that can run other commands, is not known to be harmless, so it
 *       is never allowed as a plain write. An operator who trusts a command lists it in
 *       {@code allowedCommands}.</li>
 * </ul>
 *
 * <p>The operator's {@code destructiveCommands} wins over {@code allowedCommands}. The
 * {@code minecraft:} namespace is ignored ({@code minecraft:kill} is {@code kill}). This is
 * intentionally independent of Minecraft's own permission system: an AI must never inherit OP just
 * because the server operator has it. Game text and mod-provided metadata cannot change any of
 * this; only the configuration can.</p>
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

    /**
     * Everyday vanilla commands that cannot hurt a player, destroy anything or run other commands. Left out on
     * purpose, because they can: {@code effect} (instant damage kills), {@code xp}/{@code experience} (a negative
     * amount wipes levels), {@code give} (any count, to everyone), {@code particle}, {@code playsound} and
     * {@code title} (flood every client), and {@code tellraw} (a clickable command in front of an operator). They
     * need admin and confirmation like any unrecognised command; an operator who trusts one lists it in
     * {@code allowedCommands}.
     */
    private static final Set<String> SAFE_ROOTS = Set.of(
            "say", "tell", "msg", "w", "me", "list", "seed", "help", "time", "weather");

    private final boolean allowWrites;
    private final boolean allowAdmin;
    private final Set<String> allowed;
    private final Set<String> destructive;

    public ToolPolicy(boolean allowWrites, boolean allowAdmin) {
        this(allowWrites, allowAdmin, Set.of(), Set.of());
    }

    /**
     * @param allowedCommands     extra commands that count as known safe (already normalized)
     * @param destructiveCommands extra commands that count as destructive (already normalized)
     */
    public ToolPolicy(boolean allowWrites, boolean allowAdmin, Set<String> allowedCommands,
                      Set<String> destructiveCommands) {
        this.allowWrites = allowWrites;
        this.allowAdmin = allowAdmin;
        this.allowed = allowedCommands;
        this.destructive = destructiveCommands;
    }

    public Decision checkCommand(String rawCommand, boolean confirmed) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String root = CommandRoots.normalize(command.split("\\s+", 2)[0]);
        if (root.isEmpty()) {
            return Decision.deny("empty command");
        }
        if (isDestructive(root)) {
            return needsAdmin(confirmed, "admin commands are disabled", "destructive command requires confirmation");
        }
        if (isKnownSafe(root)) {
            return allowWrites ? Decision.allow() : Decision.deny("write commands are disabled");
        }
        return needsAdmin(confirmed,
                "unrecognised command '" + root + "' needs admin access; list it in allowedCommands to trust it",
                "unrecognised command '" + root + "' requires confirmation");
    }

    private boolean isDestructive(String root) {
        return DESTRUCTIVE_ROOTS.contains(root) || destructive.contains(root);
    }

    private boolean isKnownSafe(String root) {
        return SAFE_ROOTS.contains(root) || allowed.contains(root);
    }

    private Decision needsAdmin(boolean confirmed, String disabledReason, String confirmReason) {
        if (!allowAdmin) {
            return Decision.deny(disabledReason);
        }
        return confirmed ? Decision.allow() : Decision.confirm(confirmReason);
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
