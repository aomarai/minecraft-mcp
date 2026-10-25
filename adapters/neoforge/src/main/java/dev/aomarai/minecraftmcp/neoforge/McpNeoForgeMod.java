package dev.aomarai.minecraftmcp.neoforge;

import dev.aomarai.minecraftmcp.core.ConfigException;
import dev.aomarai.minecraftmcp.core.GameServices;
import dev.aomarai.minecraftmcp.core.McpController;
import dev.aomarai.minecraftmcp.core.Side;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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
@Mod(value = "minecraft_mcp", dist = Dist.DEDICATED_SERVER)
public final class McpNeoForgeMod {

    private static final Logger LOG = LoggerFactory.getLogger("minecraft-mcp");

    private volatile McpController controller;

    public McpNeoForgeMod() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> start(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> stop());
    }

    private void start(MinecraftServer server) {
        Path configDir = FMLPaths.CONFIGDIR.get();
        McpController created = new McpController(configDir.resolve("minecraft-mcp.json"),
                configDir.resolve("minecraft-mcp-audit.jsonl"), config -> servicesFor(server), System.getenv());
        controller = created;
        try {
            created.start();
            created.status().ifPresent(status -> LOG.info("MCP server listening on port {}", status.port()));
        } catch (ConfigException e) {
            LOG.error("MCP server not started: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("MCP server failed to start", e);
        }
    }

    private static GameServices servicesFor(MinecraftServer server) {
        return new GameServices(Side.SERVER, new NeoForgeRuntimeInfo(server));
    }

    private void stop() {
        McpController stopping = controller;
        controller = null;
        if (stopping == null) {
            return;
        }
        try {
            stopping.close();
            LOG.info("MCP server stopped");
        } catch (Exception e) {
            LOG.error("MCP server did not stop cleanly", e);
        }
    }
}
