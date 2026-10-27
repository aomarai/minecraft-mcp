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

    /** The number of positions in the box; {@link Long#MAX_VALUE} when even a long cannot hold it. */
    public long volume() {
        // Each extent is computed in long: the int difference overflows for far-apart bounds (min -2^31, max 2^31-1),
        // which would make a huge box look small enough to pass the volume cap. Three such extents overflow a long
        // too, so the product saturates instead of wrapping.
        long x = (long) maxX - minX + 1;
        long y = (long) maxY - minY + 1;
        long z = (long) maxZ - minZ + 1;
        try {
            return Math.multiplyExact(Math.multiplyExact(x, y), z);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
