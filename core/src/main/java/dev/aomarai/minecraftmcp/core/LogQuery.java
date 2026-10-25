package dev.aomarai.minecraftmcp.core;

/**
 * The most recent server log lines, newest last.
 *
 * @param limit at most this many lines, 1 to {@value #MAX_LIMIT}
 */
public record LogQuery(int limit) {

    public static final int MAX_LIMIT = 200;

    public LogQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
