package dev.aomarai.minecraftmcp.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything an adapter needs to run the server: load the config, wire the policy gate and audit
 * log around the adapter's ports, and start the authenticated HTTP endpoint.
 *
 * <p>An adapter calls {@link #start} when its game server is ready and {@link #close} when it is
 * stopping. It supplies only what it can serve as {@link GameServices}, built from the loaded
 * config (some ports depend on it, such as the permission level of commands); the catalog
 * advertises exactly those tools.</p>
 */
public final class McpService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpService.class.getName());

    /** What an operator may see of a running server. Never includes the token. */
    public record Status(String host, int port, boolean allowWrites, boolean allowAdmin, int toolCount) {
        public String summary() {
            String access = allowAdmin ? "writes and admin enabled" : allowWrites ? "writes enabled" : "read-only";
            return "listening on " + host + ":" + port + ", " + access + ", " + toolCount + " tools";
        }
    }

    private final McpCore core;
    private final Status status;

    private McpService(McpCore core, Status status) {
        this.core = core;
        this.status = status;
    }

    /**
     * @param configFile JSON config; created with secure defaults and a generated token on first run
     * @param auditFile  JSONL audit log; created owner-only
     * @param services   builds the adapter's ports once the config is known
     * @throws ConfigException if the configuration is invalid
     * @throws Exception       if the HTTP endpoint cannot start (for example, the port is in use)
     */
    public static McpService start(Path configFile, Path auditFile, Function<McpConfig, GameServices> services)
            throws Exception {
        return start(configFile, auditFile, services, System.getenv());
    }

    /** As {@link #start(Path, Path, Function)}, with explicit environment overrides. */
    public static McpService start(Path configFile, Path auditFile, Function<McpConfig, GameServices> services,
                                   Map<String, String> env) throws Exception {
        ConfigLoader.Loaded loaded = ConfigLoader.load(configFile, env);
        McpConfig config = loaded.config();
        if (config.tls().isPresent()) {
            LOG.log(System.Logger.Level.INFO, "Serving HTTPS on {0} with the certificate in {1}", config.host(),
                    config.tls().get().keystore());
        } else if (!McpConfig.isLoopback(config.host())) {
            LOG.log(System.Logger.Level.WARNING, "Listening on {0}, which other machines can reach, over plain HTTP. "
                    + "Serve HTTPS with tlsKeystore or put a TLS proxy in front, and keep the port firewalled "
                    + "(docs/remote-access.md).", config.host());
        }
        GameServices ports = services.apply(config);
        ToolDispatcher dispatcher = new ToolDispatcher(config, ports,
                new JsonlAuditLog(auditFile, secrets(loaded.token(), config)));
        ToolCatalog catalog = ToolCatalog.defaults().availableFor(ports);
        McpCore core = new McpCore(config.host(), config.port(), catalog, dispatcher, loaded.token(),
                ports.find(EventFeed.class), config.exposure());
        core.start();
        return new McpService(core, new Status(config.host(), core.port(), config.allowWrites(),
                config.allowAdmin(), catalog.tools().size()));
    }

    /** What must never appear in the audit log: the token, and the keystore password when there is one. */
    private static List<String> secrets(String token, McpConfig config) {
        return config.tls().map(tls -> List.of(token, tls.password())).orElse(List.of(token));
    }

    /** The port the endpoint is listening on. */
    public int port() {
        return core.port();
    }

    public Status status() {
        return status;
    }

    @Override
    public void close() throws Exception {
        core.close();
    }
}
