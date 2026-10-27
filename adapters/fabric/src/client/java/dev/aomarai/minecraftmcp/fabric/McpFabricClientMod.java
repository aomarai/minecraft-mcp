package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.ConfigException;
import dev.aomarai.minecraftmcp.core.ConfigLoader;
import dev.aomarai.minecraftmcp.core.GameServices;
import dev.aomarai.minecraftmcp.core.McpController;
import dev.aomarai.minecraftmcp.core.Side;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The client's own MCP endpoint, for the tools only a game client can serve (issue #38). It is separate from the
 * integrated server's endpoint, which {@link McpFabricMod} starts: its own config file
 * ({@code minecraft-mcp-client.json}), audit log and port ({@value #DEFAULT_PORT}), the same secure defaults, and
 * environment variables named {@code MINECRAFT_MCP_CLIENT_*}. A problem is logged and never stops the game.
 */
public final class McpFabricClientMod implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("minecraft-mcp");
    private static final int DEFAULT_PORT = 25598;

    private final FabricClientPlayer player = new FabricClientPlayer();
    private volatile McpController controller;
    /** Runs what /mcpclient does off the client thread; a daemon, so it never keeps the game alive. */
    private final ExecutorService background = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "minecraft-mcp-client-command");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> McpClientCommand.register(dispatcher, () -> controller, background));
        ClientTickEvents.END_CLIENT_TICK.register(player::tick);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop());
    }

    private void start() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve("minecraft-mcp-client.json");
        McpController created = new McpController(configFile, configDir.resolve("minecraft-mcp-client-audit.jsonl"),
                config -> new GameServices(Side.CLIENT, new FabricClientScreenshots(), new FabricClientScreens(),
                        player),
                ConfigLoader.scopedEnvironment(System.getenv(), "CLIENT"));
        controller = created;
        try {
            ConfigLoader.seedPort(configFile, DEFAULT_PORT);
            created.start();
            created.status().ifPresent(status -> LOG.info("MCP client server listening on port {}", status.port()));
        } catch (ConfigException e) {
            LOG.error("MCP client server not started: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("MCP client server failed to start", e);
        }
    }

    private void stop() {
        background.shutdownNow();
        McpController stopping = controller;
        controller = null;
        if (stopping == null) {
            return;
        }
        try {
            stopping.close();
        } catch (Exception e) {
            LOG.error("MCP client server did not stop cleanly", e);
        }
    }
}
