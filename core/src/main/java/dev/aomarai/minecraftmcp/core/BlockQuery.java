package dev.aomarai.minecraftmcp.core;

import java.util.Objects;

/**
 * Bounded block query. Implementations must reject volumes larger than the
 * configured cap and must not scan unbounded regions.
 */
public record BlockQuery(
        String dimension,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        int limit) {

    public BlockQuery {
        Objects.requireNonNull(dimension, "dimension");
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("max coordinates must be >= min coordinates");
        }
        if (limit < 1 || limit > 4096) {
            throw new IllegalArgumentException("limit must be between 1 and 4096");
        }
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}
