package dev.aomarai.minecraftmcp.core;

import java.util.Objects;

/**
 * A bounded, paged listing of one registry.
 *
 * @param registry registry id, e.g. {@code minecraft:block} (normalized)
 * @param search   keep only ids containing this text, case-insensitive; empty means all
 * @param offset   how many matching ids to skip
 * @param limit    at most this many ids, 1 to {@value #MAX_LIMIT}
 */
public record RegistryQuery(String registry, String search, int offset, int limit) {

    public static final int MAX_LIMIT = 200;

    public RegistryQuery {
        registry = ResourceIds.parse(Objects.requireNonNull(registry, "registry"), "registry");
        search = Objects.requireNonNullElse(search, "").strip();
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
