package dev.aomarai.minecraftmcp.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Transport and policy configuration. Secrets are supplied separately so this
 * record can be logged without leaking the bearer token.
 *
 * <p>{@code allowedCommands} and {@code destructiveCommands} extend the policy's built-in command
 * lists (see {@link ToolPolicy}); names are normalized, so {@code minecraft:kill} is {@code kill}.</p>
 *
 * <p>The server binds to this machine only. A {@code host} that is not loopback (an interface address or
 * {@code 0.0.0.0}) is refused unless {@code allowRemote} says so on purpose, because the endpoint speaks
 * plain HTTP; see docs/remote-access.md for the supported way to reach it from elsewhere.</p>
 */
public record McpConfig(
        String host,
        int port,
        boolean allowWrites,
        boolean allowAdmin,
        int maxBlockVolume,
        Duration callTimeout,
        Set<String> allowedCommands,
        Set<String> destructiveCommands,
        boolean allowRemote) {

    public McpConfig(String host, int port, boolean allowWrites, boolean allowAdmin, int maxBlockVolume,
                     Duration callTimeout) {
        this(host, port, allowWrites, allowAdmin, maxBlockVolume, callTimeout, Set.of(), Set.of());
    }

    public McpConfig(String host, int port, boolean allowWrites, boolean allowAdmin, int maxBlockVolume,
                     Duration callTimeout, Set<String> allowedCommands, Set<String> destructiveCommands) {
        this(host, port, allowWrites, allowAdmin, maxBlockVolume, callTimeout, allowedCommands, destructiveCommands, false);
    }

    public McpConfig {
        host = Objects.requireNonNullElse(host, "127.0.0.1");
        if (!allowRemote && !isLoopback(host)) {
            throw new IllegalArgumentException("host '" + host + "' is not this machine, and the endpoint speaks plain HTTP; "
                    + "set allowRemote to bind it anyway, or keep loopback and put a TLS proxy in front (docs/remote-access.md)");
        }
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

    /**
     * Whether {@code host} can only be reached from this machine: {@code localhost} or a loopback address. A
     * wildcard ({@code 0.0.0.0}, {@code ::}) and any other name or address are not. Names are not looked up.
     */
    public static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        // Only literals are parsed, so this never asks DNS: a name that is not an address is not loopback.
        boolean literal = host.contains(":") || host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        if (!literal) {
            return false;
        }
        try {
            return InetAddress.getByName(host.replaceAll("^\\[|]$", "")).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static McpConfig defaults() {
        return new McpConfig("127.0.0.1", 25599, false, false, 32768, Duration.ofSeconds(8));
    }
}
