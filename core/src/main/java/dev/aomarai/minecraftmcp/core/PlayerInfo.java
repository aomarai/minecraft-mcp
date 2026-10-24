package dev.aomarai.minecraftmcp.core;

/** Immutable player snapshot returned by an adapter. */
public record PlayerInfo(
        String name,
        String uuid,
        String dimension,
        double x,
        double y,
        double z,
        boolean op) {
}
