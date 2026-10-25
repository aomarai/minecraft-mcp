package dev.aomarai.minecraftmcp.vanilla;

import com.mojang.brigadier.CommandDispatcher;
import dev.aomarai.minecraftmcp.core.ConfigException;
import dev.aomarai.minecraftmcp.core.EventBuffer;
import dev.aomarai.minecraftmcp.core.FileLogReader;
import dev.aomarai.minecraftmcp.core.GamePort;
import dev.aomarai.minecraftmcp.core.GameServices;
import dev.aomarai.minecraftmcp.core.McpConfig;
import dev.aomarai.minecraftmcp.core.McpController;
import dev.aomarai.minecraftmcp.core.Side;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the loader adapters share: the lifecycle of the MCP server, the {@code /mcp} command,
 * the event buffer, and the ports that need only vanilla Minecraft classes. A loader adapter supplies
 * what really differs: when the game server starts and stops, where its config directory is, how to
 * describe the runtime, and the callbacks that feed {@link #events()}.
 *
 * <p>A configuration or start-up problem is logged and leaves the game server running without MCP.
 * It never crashes the game.</p>
 */
public final class McpHost {

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

    /** Where the loader's event callbacks publish chat, joins, leaves and deaths. */
    public EventBuffer events() {
        return events;
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        McpCommand.register(dispatcher, () -> controller, background);
    }

    /**
     * @param configDir  where {@code minecraft-mcp.json} and the audit log live
     * @param logFile    the game's {@code latest.log}
     * @param loaderPorts what only the loader can provide, at least the {@code RuntimeInfo}
     */
    public void start(MinecraftServer server, Side side, Path configDir, Path logFile, GamePort... loaderPorts) {
        McpController created = new McpController(configDir.resolve("minecraft-mcp.json"),
                configDir.resolve("minecraft-mcp-audit.jsonl"),
                config -> servicesFor(server, side, config, logFile, loaderPorts), System.getenv());
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

    public void stop() {
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

    private GameServices servicesFor(MinecraftServer server, Side side, McpConfig config, Path logFile,
                                     GamePort[] loaderPorts) {
        // Commands run at the lowest level that fits what is enabled, so the game itself refuses
        // admin commands unless admin access is on. The core's policy has already decided by then.
        int commandLevel = config.allowAdmin() ? ADMIN_LEVEL : GAMEMASTER_LEVEL;
        List<GamePort> ports = new ArrayList<>(List.of(loaderPorts));
        ports.add(new VanillaPlayerReader(server));
        ports.add(new VanillaWorldReader(server));
        ports.add(new VanillaRegistryReader(server));
        ports.add(new VanillaEntityReader(server));
        ports.add(new VanillaWorldStateReader(server));
        ports.add(new VanillaPermissionReader(server));
        ports.add(new VanillaInventoryReader(server));
        ports.add(new FileLogReader(logFile));
        ports.add(events);
        ports.add(new VanillaCommandRunner(server, commandLevel));
        return new GameServices(side, ports.toArray(new GamePort[0]));
    }
}
