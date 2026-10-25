package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.BlockQuery;
import dev.aomarai.minecraftmcp.core.BlockScanner;
import dev.aomarai.minecraftmcp.core.MainThread;
import dev.aomarai.minecraftmcp.core.WorldReader;
import dev.aomarai.minecraftmcp.core.WorldSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Reads blocks on the main thread. Dimensions and blocks are resolved by registry id, so modded
 * dimensions and blocks work without special handling. It never loads or generates chunks:
 * positions in unloaded chunks are skipped by the scan.
 */
final class FabricWorldReader implements WorldReader {

    private final MinecraftServer server;

    FabricWorldReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<WorldSnapshot> snapshot(BlockQuery query) {
        return MainThread.call(server, () -> {
            ServerLevel level = level(query.dimension());
            return BlockScanner.scan(query, (x, y, z) -> blockAt(level, x, y, z));
        });
    }

    private ServerLevel level(String dimension) {
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

    private static Optional<String> blockAt(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.isOutsideBuildHeight(y) || !level.hasChunkAt(pos)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
