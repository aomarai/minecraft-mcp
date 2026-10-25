package dev.aomarai.minecraftmcp.vanilla;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Finds a dimension by its registry id, so modded dimensions work like vanilla ones. */
final class VanillaDimensions {

    private VanillaDimensions() {
    }

    /** Call on the main thread. An unknown dimension is reported together with the available ones. */
    static ServerLevel level(MinecraftServer server, String dimension) {
        return GameCompat.level(server, dimension).orElseThrow(() -> new IllegalArgumentException(
                "unknown dimension '" + dimension + "'; available: " + String.join(", ", GameCompat.levelIds(server))));
    }
}
