package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.ConfigLoader;
import dev.aomarai.minecraftmcp.core.McpService;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The standalone server for a game server that has no mod: {@code java -jar minecraft-mcp-universal.jar
 * [config-directory]}. It reads {@code minecraft-mcp.json} in that directory (the current one by default),
 * creates it with a generated token on first run, and serves MCP over HTTP, as the mods do, with the same
 * policy and audit. See docs/universal.md.
 *
 * <p>A configuration problem is thrown, which makes the JVM exit with a message and a non-zero status.</p>
 */
public final class UniversalMain {

    private static final long CHECK_INTERVAL_MILLIS = 500;

    private UniversalMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args, Path.of("."), System.getenv(), System.out, Runtime.getRuntime()::addShutdownHook);
    }

    /** Starts the server and keeps running until the thread is interrupted or the JVM shuts down. */
    static void run(String[] args, Path workingDirectory, Map<String, String> env, PrintStream out,
                    Consumer<Thread> shutdownHooks) throws Exception {
        Running running = start(args, workingDirectory, env, out);
        // Stopping the process (Ctrl+C, a service manager) closes everything cleanly.
        shutdownHooks.accept(new Thread(running::close, "minecraft-mcp-shutdown"));
        boolean interrupted = false;
        while (!interrupted) {
            try {
                Thread.sleep(CHECK_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        running.close();
        // Closing came first, so it ran without the flag; set again, the caller still sees that it was interrupted.
        Thread.currentThread().interrupt();
    }

    /** Everything that has been started, so that it can be stopped again. */
    static final class Running implements AutoCloseable {
        private final int port;
        private final AutoCloseable mcp;
        private final UniversalServices universal;

        Running(int port, AutoCloseable mcp, UniversalServices universal) {
            this.port = port;
            this.mcp = mcp;
            this.universal = universal;
        }

        int port() {
            return port;
        }

        @Override
        public void close() {
            try {
                mcp.close();
            } catch (Exception e) {
                // Shutting down: nothing more can be done about it, and the rest must still be stopped.
            } finally {
                universal.close();
            }
        }
    }

    static Running start(String[] args, Path workingDirectory, Map<String, String> env, PrintStream out) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("usage: java -jar minecraft-mcp-universal.jar [config-directory]");
        }
        Path directory = args.length == 1 ? Path.of(args[0]) : workingDirectory;
        Path configFile = directory.resolve("minecraft-mcp.json");
        Path auditFile = directory.resolve("minecraft-mcp-audit.jsonl");

        ConfigLoader.Loaded loaded = ConfigLoader.load(configFile, env);
        UniversalConfig config = UniversalSettings.parse(ConfigLoader.universalSection(configFile), env);
        UniversalServices universal = UniversalServices.start(config, loaded.config().callTimeout());
        try {
            McpService mcp = McpService.start(configFile, auditFile, ignored -> universal.services(), env);
            out.println("Minecraft MCP (universal) " + mcp.status().summary());
            out.println("Configuration: " + configFile.toAbsolutePath()
                    + (loaded.created() ? " (created; it holds the token, which is not shown here)" : ""));
            return new Running(mcp.port(), mcp, universal);
        } catch (Exception e) {
            universal.close();
            throw e;
        }
    }
}
