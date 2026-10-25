package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.ConfigException;
import dev.aomarai.minecraftmcp.core.GameServices;
import dev.aomarai.minecraftmcp.core.McpService;
import dev.aomarai.minecraftmcp.core.Side;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Starts the MCP server when the game server is ready and stops it when the game server is
 * stopping. Keep this class thin: the logic lives in {@code core}.
 *
 * <p>A configuration or start-up problem is logged and leaves the game server running without MCP.
 * It never crashes the game.</p>
 */
public final class McpFabricMod implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("minecraft-mcp");

    private McpService service;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
    }

    private void start(MinecraftServer server) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        GameServices services = new GameServices(new FabricRuntimeInfo(server), null, null, null, Side.SERVER);
        try {
            service = McpService.start(configDir.resolve("minecraft-mcp.json"),
                    configDir.resolve("minecraft-mcp-audit.jsonl"), services);
            LOG.info("MCP server listening on port {}", service.port());
        } catch (ConfigException e) {
            LOG.error("MCP server not started: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("MCP server failed to start", e);
        }
    }

    private void stop() {
        if (service == null) {
            return;
        }
        try {
            service.close();
            LOG.info("MCP server stopped");
        } catch (Exception e) {
            LOG.error("MCP server did not stop cleanly", e);
        } finally {
            service = null;
        }
    }
}
