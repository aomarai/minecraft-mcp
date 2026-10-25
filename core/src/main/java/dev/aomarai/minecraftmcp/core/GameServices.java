package dev.aomarai.minecraftmcp.core;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The capability ports an adapter provides, and the side it runs on. A port the
 * platform cannot serve is {@code null}; {@link ToolCatalog#availableFor} then
 * leaves out the tools that need it.
 */
public record GameServices(
        RuntimeInfo info,
        PlayerReader players,
        WorldReader world,
        CommandRunner commands,
        Side side) {

    public GameServices {
        Objects.requireNonNull(side, "side");
    }

    /** A server-side adapter with no capabilities. */
    public static GameServices none() {
        return new GameServices(null, null, null, null, Side.SERVER);
    }

    public Set<Capability> capabilities() {
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        if (info != null) {
            capabilities.add(Capability.RUNTIME_INFO);
        }
        if (players != null) {
            capabilities.add(Capability.PLAYERS);
        }
        if (world != null) {
            capabilities.add(Capability.WORLD);
        }
        if (commands != null) {
            capabilities.add(Capability.COMMANDS);
        }
        return capabilities;
    }
}
