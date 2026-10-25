package dev.aomarai.minecraftmcp.core;

import java.util.Objects;

/**
 * Entities within a radius of a point, nearest first.
 *
 * @param type  keep only this entity type (a resource location); empty means any
 * @param limit at most this many entities, 1 to {@value #MAX_LIMIT}
 */
public record EntityQuery(String dimension, double x, double y, double z, double radius, String type, int limit) {

    public static final double MAX_RADIUS = 64;
    public static final int MAX_LIMIT = 200;

    public EntityQuery {
        Objects.requireNonNull(dimension, "dimension");
        if (!(radius >= 1 && radius <= MAX_RADIUS)) {
            throw new IllegalArgumentException("radius must be between 1 and " + (int) MAX_RADIUS);
        }
        String requested = Objects.requireNonNullElse(type, "").strip();
        type = requested.isEmpty() ? "" : ResourceIds.parse(requested, "type");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
