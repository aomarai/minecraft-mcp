package dev.aomarai.minecraftmcp.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Transport and policy configuration. Secrets are supplied separately so this
 * record can be logged without leaking the bearer token.
 *
 * <p>{@code allowedCommands} and {@code destructiveCommands} extend the policy's built-in command
 * lists (see {@link ToolPolicy}); names are normalized, so {@code minecraft:kill} is {@code kill}.</p>
 *
 * <p>The server binds to this machine only. A {@code host} that is not loopback (an interface address or
 * {@code 0.0.0.0}) is refused unless {@code allowRemote} says so on purpose. Without {@code tls} the endpoint
 * speaks plain HTTP; with it, it serves HTTPS itself. {@code allowedHosts} are extra names clients may use in the
 * {@code Host} header; see docs/remote-access.md.</p>
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
        boolean allowRemote,
        Set<String> allowedHosts,
        Optional<Tls> tls) {

    /** A host name or address as it appears in a {@code Host} header, without scheme, port or path. */
    private static final Pattern HOST_NAME = Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?|\\[[0-9a-f:.]+]");

    public McpConfig(String host, int port, boolean allowWrites, boolean allowAdmin, int maxBlockVolume,
                     Duration callTimeout) {
        this(host, port, allowWrites, allowAdmin, maxBlockVolume, callTimeout, Set.of(), Set.of());
    }

    public McpConfig(String host, int port, boolean allowWrites, boolean allowAdmin, int maxBlockVolume,
                     Duration callTimeout, Set<String> allowedCommands, Set<String> destructiveCommands) {
        this(host, port, allowWrites, allowAdmin, maxBlockVolume, callTimeout, allowedCommands, destructiveCommands, false);
    }

    public McpConfig(String host, int port, boolean allowWrites, boolean allowAdmin, int maxBlockVolume,
                     Duration callTimeout, Set<String> allowedCommands, Set<String> destructiveCommands,
                     boolean allowRemote) {
        this(host, port, allowWrites, allowAdmin, maxBlockVolume, callTimeout, allowedCommands, destructiveCommands,
                allowRemote, Set.of(), Optional.empty());
    }

    public McpConfig {
        host = Objects.requireNonNullElse(host, "127.0.0.1");
        if (!allowRemote && !isLoopback(host)) {
            throw new IllegalArgumentException("host '" + host + "' is not this machine; set allowRemote to bind it anyway "
                    + "(and serve HTTPS with tlsKeystore), or keep loopback and put a TLS proxy in front "
                    + "(docs/remote-access.md)");
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
        allowedHosts = normalizeHosts(Objects.requireNonNullElse(allowedHosts, Set.of()));
        tls = Objects.requireNonNullElse(tls, Optional.empty());
    }

    private static Set<String> normalizeHosts(Set<String> names) {
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String name : names) {
            String host = name.strip().toLowerCase(java.util.Locale.ROOT);
            if (!HOST_NAME.matcher(host).matches()) {
                throw new IllegalArgumentException("allowedHosts entry '" + name + "' must be a host name or address "
                        + "without scheme, port or path (an IPv6 address in brackets)");
            }
            normalized.add(host);
        }
        return Set.copyOf(normalized);
    }

    /** What reaches clients beyond this machine: HTTPS, and the extra host names they may use. */
    public Exposure exposure() {
        return new Exposure(tls, allowedHosts);
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
