package dev.aomarai.minecraftmcp.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything an adapter needs to run the server: load the config, wire the policy gate and audit
 * log around the adapter's ports, and start the authenticated HTTP endpoint.
 *
 * <p>An adapter calls {@link #start} when its game server is ready and {@link #close} when it is
 * stopping. It supplies only what it can serve as {@link GameServices}; the catalog advertises
 * exactly those tools.</p>
 */
public final class McpService implements AutoCloseable {

    private final McpCore core;

    private McpService(McpCore core) {
        this.core = core;
    }

    /**
     * @param configFile JSON config; created with secure defaults and a generated token on first run
     * @param auditFile  JSONL audit log; created owner-only
     * @throws ConfigException if the configuration is invalid
     * @throws Exception       if the HTTP endpoint cannot start (for example, the port is in use)
     */
    public static McpService start(Path configFile, Path auditFile, GameServices services) throws Exception {
        ConfigLoader.Loaded loaded = ConfigLoader.load(configFile);
        McpConfig config = loaded.config();
        ToolDispatcher dispatcher = new ToolDispatcher(config, services,
                new JsonlAuditLog(auditFile, List.of(loaded.token())));
        McpCore core = new McpCore(config.host(), config.port(),
                ToolCatalog.defaults().availableFor(services), dispatcher, loaded.token());
        core.start();
        return new McpService(core);
    }

    /** The port the endpoint is listening on. */
    public int port() {
        return core.port();
    }

    @Override
    public void close() throws Exception {
        core.close();
    }
}
