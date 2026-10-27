package dev.aomarai.minecraftmcp.neoforge;

import dev.aomarai.minecraftmcp.core.Side;
import dev.aomarai.minecraftmcp.vanilla.McpHost;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Connects the shared {@link McpHost} to NeoForge's lifecycle and events. Keep this class thin: the
 * logic lives in {@code core} and in the vanilla code every adapter shares.
 *
 * <p>It loads on a dedicated server and in a game client, where {@code ServerStartedEvent} fires for the integrated
 * server (single-player and LAN), which is then served exactly like a dedicated one. The client-only tools stay
 * Fabric-only.</p>
 */
@Mod("minecraft_mcp")
public final class McpNeoForgeMod {

    private final McpHost host = new McpHost();

    public McpNeoForgeMod() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> host.registerCommands(event.getDispatcher()));
        NeoForgeEventPublisher.register(host.events());
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> start(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> host.stop());
    }

    private void start(MinecraftServer server) {
        host.start(server, Side.SERVER, FMLPaths.CONFIGDIR.get(),
                FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log"), new NeoForgeRuntimeInfo(server));
    }
}
