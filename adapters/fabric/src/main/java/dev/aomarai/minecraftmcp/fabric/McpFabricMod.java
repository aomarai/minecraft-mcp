package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.Side;
import dev.aomarai.minecraftmcp.vanilla.McpHost;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Connects the shared {@link McpHost} to Fabric's lifecycle and events. Keep this class thin: the
 * logic lives in {@code core} and in the vanilla code every adapter shares.
 */
public final class McpFabricMod implements ModInitializer {

    private final McpHost host = new McpHost();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                host.registerCommands(dispatcher));
        FabricEventPublisher.register(host.events());
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> host.stop());
    }

    private void start(MinecraftServer server) {
        FabricLoader loader = FabricLoader.getInstance();
        host.start(server, side(), loader.getConfigDir(), loader.getGameDir().resolve("logs").resolve("latest.log"),
                new FabricRuntimeInfo(server));
    }

    /** Dedicated server or client, as the loader reports it, so client-only tools are never offered by a server. */
    private static Side side() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT ? Side.CLIENT : Side.SERVER;
    }
}
