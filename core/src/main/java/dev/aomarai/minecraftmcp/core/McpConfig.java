package dev.aomarai.minecraftmcp.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Transport and policy configuration. Secrets are supplied separately so this
 * record can be logged without leaking the bearer token.
 *
 * <p>{@code allowedCommands} and {@code destructiveCommands} extend the policy's built-in command
 * lists (see {@link ToolPolicy}); names are normalized, so {@code minecraft:kill} is {@code kill}.</p>
 */
public record McpConfig(
        String host,
        int port,
        boolean allowWrites,
        boolean allowAdmin,
        int maxBlockVolume,
        Duration callTimeout,
        Set<String> allowedCommands,
        Set<String> destructiveCommands) {

    public McpConfig(String host, int port, boolean allowWrites, boolean allowAdmin, int maxBlockVolume,
                     Duration callTimeout) {
        this(host, port, allowWrites, allowAdmin, maxBlockVolume, callTimeout, Set.of(), Set.of());
    }

    public McpConfig {
        host = Objects.requireNonNullElse(host, "127.0.0.1");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (maxBlockVolume < 1 || maxBlockVolume > 1_000_000) {
            throw new IllegalArgumentException("maxBlockVolume must be between 1 and 1000000");
        }
        callTimeout = callTimeout == null ? Duration.ofSeconds(8) : callTimeout;
        if (callTimeout.isNegative() || callTimeout.isZero()) {
            throw new IllegalArgumentException("callTimeout must be positive");
        }
        allowedCommands = CommandRoots.normalizeAll(Objects.requireNonNullElse(allowedCommands, Set.of()),
                "allowedCommands");
        destructiveCommands = CommandRoots.normalizeAll(Objects.requireNonNullElse(destructiveCommands, Set.of()),
                "destructiveCommands");
    }

    public static McpConfig defaults() {
        return new McpConfig("127.0.0.1", 25599, false, false, 32768, Duration.ofSeconds(8));
    }
}
