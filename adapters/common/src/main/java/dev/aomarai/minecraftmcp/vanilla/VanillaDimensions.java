package dev.aomarai.minecraftmcp.vanilla;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.stream.Collectors;

/** Finds a dimension by its registry id, so modded dimensions work like vanilla ones. */
final class VanillaDimensions {

    private VanillaDimensions() {
    }

    /** Call on the main thread. An unknown dimension is reported together with the available ones. */
    static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) {
            String available = server.levelKeys().stream()
                    .map(key -> key.location().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("unknown dimension '" + dimension + "'; available: " + available);
        }
        return level;
    }
}
