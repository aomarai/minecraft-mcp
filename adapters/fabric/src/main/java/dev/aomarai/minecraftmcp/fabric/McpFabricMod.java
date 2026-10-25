package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.ConfigException;
import dev.aomarai.minecraftmcp.core.EventBuffer;
import dev.aomarai.minecraftmcp.core.FileLogReader;
import dev.aomarai.minecraftmcp.core.GameServices;
import dev.aomarai.minecraftmcp.core.McpConfig;
import dev.aomarai.minecraftmcp.core.McpController;
import dev.aomarai.minecraftmcp.core.Side;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts the MCP server when the game server is ready and stops it when the game server is
 * stopping, and offers the operator's {@code /mcp} command. Keep this class thin: the logic lives
 * in {@code core}.
 *
 * <p>A configuration or start-up problem is logged and leaves the game server running without MCP.
 * It never crashes the game.</p>
 */
public final class McpFabricMod implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("minecraft-mcp");
    /** Vanilla permission level 2: /give, /setblock, /tp, /kill ... */
    private static final int GAMEMASTER_LEVEL = 2;
    /** Vanilla permission level 4: everything, including /stop. */
    private static final int ADMIN_LEVEL = 4;

    /** Recent events kept for clients to read; older ones are dropped. */
    private static final int EVENT_CAPACITY = 500;

    /** Runs the {@code /mcp} commands off the game thread. Daemon, so it can never block JVM exit. */
    private final ExecutorService background = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "minecraft-mcp-control");
        thread.setDaemon(true);
        return thread;
    });

    /** Owned here, not by the controller, so events survive a reload or a token rotation. */
    private final EventBuffer events = new EventBuffer(EVENT_CAPACITY);

    private volatile McpController controller;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                McpCommand.register(dispatcher, () -> controller, background));
        FabricEventPublisher.register(events);
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
    }

    private void start(MinecraftServer server) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        McpController created = new McpController(configDir.resolve("minecraft-mcp.json"),
                configDir.resolve("minecraft-mcp-audit.jsonl"), config -> servicesFor(server, config, events), System.getenv());
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

    private static GameServices servicesFor(MinecraftServer server, McpConfig config, EventBuffer events) {
        // Commands run at the lowest level that fits what is enabled, so the game itself refuses
        // admin commands unless admin access is on. The core's policy has already decided by then.
        int commandLevel = config.allowAdmin() ? ADMIN_LEVEL : GAMEMASTER_LEVEL;
        return new GameServices(side(),
                new FabricRuntimeInfo(server),
                new FabricPlayerReader(server),
                new FabricWorldReader(server),
                new FabricRegistryReader(server),
                new FabricEntityReader(server),
                new FabricWorldStateReader(server),
                new FabricPermissionReader(server),
                new FabricInventoryReader(server),
                new FileLogReader(FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log")),
                events,
                new FabricCommandRunner(server, commandLevel));
    }

    /** Dedicated server or client, as the loader reports it, so client-only tools are never offered by a server. */
    private static Side side() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT ? Side.CLIENT : Side.SERVER;
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
