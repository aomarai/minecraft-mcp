package dev.aomarai.minecraftmcp.core;

import java.util.Optional;
import java.util.Set;

/**
 * How the listener is reached by anyone other than this machine: whether it serves HTTPS itself, and which extra
 * host names a client may use to reach it (the name in the {@code Host} header, which is checked to defeat DNS
 * rebinding; the loopback names and the bind address are always accepted).
 */
public record Exposure(Optional<Tls> tls, Set<String> allowedHosts) {

    /** Plain HTTP, no extra host names: what a loopback endpoint needs. */
    public static final Exposure LOCAL = new Exposure(Optional.empty(), Set.of());

    public Exposure {
        allowedHosts = Set.copyOf(allowedHosts);
    }

    /** {@code http} or {@code https}, as clients see the endpoint. */
    String scheme() {
        return tls.isPresent() ? "https" : "http";
    }
}
