package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.EventBuffer;
import dev.aomarai.minecraftmcp.core.FileLogReader;
import dev.aomarai.minecraftmcp.core.GamePort;
import dev.aomarai.minecraftmcp.core.GameServices;
import dev.aomarai.minecraftmcp.core.Side;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ports the universal tier can offer, built from what it was told about the server. The core then
 * advertises exactly the tools those ports can serve, so a tool that cannot work here is absent from
 * {@code tools/list} rather than failing when called.
 *
 * <ul>
 *   <li>always: running commands (and with them every typed write and admin tool), and {@code server_info};</li>
 *   <li>with the server folder: the log and the chat, join and leave events;</li>
 *   <li>with the server folder and Query: the player list (it needs Query for the names and the folder for
 *       who is an operator, so it never reports an operator status it does not know).</li>
 * </ul>
 *
 * <p>Block, registry, entity, world-state, inventory and permission tools are not offered: RCON can run
 * commands but cannot read the world.</p>
 */
public final class UniversalServices implements AutoCloseable {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration LOG_POLL = Duration.ofMillis(500);
    private static final int EVENT_CAPACITY = 500;

    private final GameServices services;
    private final RconClient rcon;
    private final LogTailer tailer;
    private final ExecutorService playerWorker;

    /** @param commandTimeout how long RCON and Query calls made on behalf of a tool may take */
    public static UniversalServices start(UniversalConfig config, Duration commandTimeout) {
        return new UniversalServices(config, commandTimeout, Clock.systemUTC());
    }

    UniversalServices(UniversalConfig config, Duration commandTimeout, Clock clock) {
        this.rcon = new RconClient(config.rcon());
        QueryClient query = config.queryPort().isPresent()
                ? new QueryClient(config.rcon().host(), config.queryPort().getAsInt()) : null;
        List<GamePort> ports = new ArrayList<>();
        ports.add(rcon);
        ports.add(new UniversalRuntimeInfo(() -> {
            if (query == null) {
                throw new java.io.IOException("Query is not configured");
            }
            return query.query(QUERY_TIMEOUT).version();
        }, clock));

        LogTailer started = null;
        ExecutorService playerWorker = null;
        if (config.serverDirectory().isPresent()) {
            Path directory = config.serverDirectory().get();
            Path log = directory.resolve("logs").resolve("latest.log");
            EventBuffer events = new EventBuffer(EVENT_CAPACITY);
            started = new LogTailer(log, events::publish);
            started.start(LOG_POLL);
            ports.add(new FileLogReader(log));
            ports.add(events);
            if (query != null) {
                playerWorker = Executors.newSingleThreadExecutor(DaemonThreads.named("minecraft-mcp-players"));
                ports.add(new UniversalPlayerReader(() -> query.query(commandTimeout).players(), rcon,
                        directory.resolve("ops.json"), commandTimeout, playerWorker));
            }
        }
        this.tailer = started;
        this.playerWorker = playerWorker;
        this.services = new GameServices(Side.SERVER, ports.toArray(new GamePort[0]));
    }

    public GameServices services() {
        return services;
    }

    @Override
    public void close() {
        rcon.close();
        if (tailer != null) {
            tailer.close();
        }
        if (playerWorker != null) {
            playerWorker.shutdownNow();
        }
    }
}
