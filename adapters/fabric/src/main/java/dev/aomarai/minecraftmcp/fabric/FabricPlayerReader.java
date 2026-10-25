package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.MainThread;
import dev.aomarai.minecraftmcp.core.PlayerInfo;
import dev.aomarai.minecraftmcp.core.PlayerReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Lists the connected players, read on the main thread. */
final class FabricPlayerReader implements PlayerReader {

    private final MinecraftServer server;

    FabricPlayerReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<List<PlayerInfo>> listPlayers() {
        return MainThread.call(server, () -> server.getPlayerList().getPlayers().stream()
                .map(this::describe)
                .toList());
    }

    private PlayerInfo describe(ServerPlayer player) {
        return new PlayerInfo(
                player.getGameProfile().getName(),
                player.getUUID().toString(),
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                server.getPlayerList().isOp(player.getGameProfile()));
    }
}
