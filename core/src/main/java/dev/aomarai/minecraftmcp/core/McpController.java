package dev.aomarai.minecraftmcp.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Owns the running {@link McpService} for an adapter and offers the operator's controls: report
 * status, reload the configuration, and rotate the token. Adapters wire these to an in-game
 * command; they never touch the token.
 *
 * <p>Reload and rotation restart the endpoint, which ends current MCP sessions: clients
 * reconnect, and after a rotation they need the new token from the config file. A reload checks
 * that the new configuration is valid <em>before</em> stopping anything, so a typo cannot take a
 * working server down.</p>
 */
public final class McpController implements AutoCloseable {

    private final Path configFile;
    private final Path auditFile;
    private final Function<McpConfig, GameServices> services;
    private final Map<String, String> env;
    private McpService service;

    public McpController(Path configFile, Path auditFile, Function<McpConfig, GameServices> services,
                         Map<String, String> env) {
        this.configFile = configFile;
        this.auditFile = auditFile;
        this.services = services;
        this.env = env;
    }

    public synchronized void start() throws Exception {
        service = McpService.start(configFile, auditFile, services, env);
    }

    /** The running server's status, or empty if it is not running. */
    public synchronized Optional<McpService.Status> status() {
        return Optional.ofNullable(service).map(McpService::status);
    }

    /**
     * Re-reads the configuration and restarts the endpoint with it.
     *
     * @throws ConfigException if the new configuration is invalid; the running server is untouched
     * @throws Exception       if the restarted endpoint cannot start; the server is then not running
     */
    public synchronized McpService.Status reload() throws Exception {
        ConfigLoader.load(configFile, env);
        stopCurrent();
        start();
        return service.status();
    }

    /**
     * Writes a new random token to the config file and restarts the endpoint, which invalidates the
     * old token immediately. The token itself is never returned.
     *
     * @throws ConfigException if the token comes from the environment, where this cannot change it
     */
    public synchronized void rotateToken() throws Exception {
        if (env.containsKey(ConfigLoader.TOKEN_ENV)) {
            throw new ConfigException(ConfigLoader.TOKEN_ENV + " is set, so it supplies the token; change that instead");
        }
        ConfigLoader.rotateToken(configFile);
        stopCurrent();
        start();
    }

    @Override
    public synchronized void close() throws Exception {
        stopCurrent();
    }

    private void stopCurrent() throws Exception {
        McpService running = service;
        service = null;
        if (running != null) {
            running.close();
        }
    }
}
