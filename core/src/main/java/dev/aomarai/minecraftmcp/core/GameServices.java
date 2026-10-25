package dev.aomarai.minecraftmcp.core;

/**
 * The capability ports an adapter provides. A port the platform cannot serve is
 * {@code null}, and tools that need it report that they are unsupported.
 */
public record GameServices(
        RuntimeInfo info,
        PlayerReader players,
        WorldReader world,
        CommandRunner commands) {

    public static GameServices none() {
        return new GameServices(null, null, null, null);
    }
}
