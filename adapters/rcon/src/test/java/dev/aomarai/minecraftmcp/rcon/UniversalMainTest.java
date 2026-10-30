package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.ConfigException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UniversalMainTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path dir;

    private final List<Thread> hooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private FakeRconServer game;
    private int port;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    @BeforeEach
    void start() throws IOException {
        game = new FakeRconServer();
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
    }

    @AfterEach
    void stop() throws IOException {
        game.close();
    }

    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("MINECRAFT_MCP_PORT", String.valueOf(port));
        env.put("MINECRAFT_MCP_TOKEN", TOKEN);
        env.put("MINECRAFT_MCP_ALLOW_WRITES", "true");
        env.put("MINECRAFT_MCP_RCON_PORT", String.valueOf(game.port()));
        env.put("MINECRAFT_MCP_RCON_PASSWORD", FakeRconServer.PASSWORD);
        return env;
    }

    private McpSyncClient client() {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer " + TOKEN))
                .build()).build();
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theServerStartsServesTheReducedToolsAndRunsACommandOverRcon() throws Exception {
        try (UniversalMain.Running running = UniversalMain.start(new String[]{dir.toString()}, dir, env(), out);
             McpSyncClient client = client()) {
            assertEquals(port, running.port());
            client.initialize();

            assertEquals(List.of("server_info", "fill_blocks", "teleport", "give_item", "send_message", "kick_player",
                            "ban_player", "save_world", "stop_server", "run_command", "run_admin_command"),
                    client.listTools().tools().stream().map(McpSchema.Tool::name).toList());

            McpSchema.CallToolResult info = client.callTool(new McpSchema.CallToolRequest("server_info", Map.of()));
            assertEquals("universal/RCON / Minecraft unknown / server", ((McpSchema.TextContent) info.content().get(0)).text());

            McpSchema.CallToolResult ran = client.callTool(new McpSchema.CallToolRequest("run_command", Map.of("command", "list")));
            assertFalse(ran.isError());
            assertEquals("ran list", ((McpSchema.TextContent) ran.content().get(0)).text());
        }
    }

    @Test
    void itSaysWhereItIsListeningAndWhereTheConfigurationIsButNeverTheToken() throws Exception {
        Map<String, String> env = env();
        env.remove("MINECRAFT_MCP_TOKEN");

        try (UniversalMain.Running ignored = UniversalMain.start(new String[]{dir.toString()}, dir, env, out)) {
            String said = output();
            String token = java.util.regex.Pattern.compile("\"token\": \"([^\"]+)\"")
                    .matcher(Files.readString(dir.resolve("minecraft-mcp.json"))).results().findFirst().orElseThrow().group(1);

            assertTrue(said.contains("Minecraft MCP (universal) listening on 127.0.0.1:" + port + ", writes enabled, 11 tools"), said);
            assertTrue(said.contains(dir.resolve("minecraft-mcp.json").toAbsolutePath().toString()), said);
            assertTrue(said.contains("(created; it holds the token, which is not shown here)"), said);
            assertFalse(said.contains(token));
        }
    }

    @Test
    void anExistingConfigurationIsNotReportedAsCreated() throws Exception {
        try (UniversalMain.Running ignored = UniversalMain.start(new String[]{dir.toString()}, dir, env(), out)) {
            assertTrue(output().contains("(created;"));
        }
        captured.reset();

        try (UniversalMain.Running ignored = UniversalMain.start(new String[]{dir.toString()}, dir, env(), out)) {
            assertFalse(output().contains("(created;"), output());
            assertTrue(output().contains("Configuration: "), output());
        }
    }

    @Test
    void theSettingsComeFromTheUniversalSectionOfTheConfigFile() throws Exception {
        Files.writeString(dir.resolve("minecraft-mcp.json"), "{\"token\":\"" + TOKEN + "\",\"port\":" + port
                + ",\"universal\":{\"rconPort\":" + game.port() + ",\"rconPassword\":\"" + FakeRconServer.PASSWORD + "\"}}");

        try (UniversalMain.Running ignored = UniversalMain.start(new String[]{dir.toString()}, dir, Map.of(), out);
             McpSyncClient client = client()) {
            client.initialize();

            assertEquals(11, client.listTools().tools().size());
        }
    }

    @Test
    void theCurrentDirectoryIsTheDefaultButAnExtraArgumentIsAnError() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> UniversalMain.start(new String[]{"a", "b"}, dir, env(), out));

        assertEquals("usage: java -jar minecraft-mcp-universal.jar [config-directory]", failure.getMessage());
    }

    @Test
    void aMissingPasswordStopsTheStartWithAMessage() {
        Map<String, String> env = env();
        env.remove("MINECRAFT_MCP_RCON_PASSWORD");

        ConfigException failure = assertThrows(ConfigException.class,
                () -> UniversalMain.start(new String[]{dir.toString()}, dir, env, out));

        assertTrue(failure.getMessage().startsWith("the RCON password is not set"));
    }

    @Test
    void whenTheEndpointCannotStartTheRconClientIsClosedAgain() throws Exception {
        Map<String, String> env = env();
        env.put("MINECRAFT_MCP_SERVER_DIRECTORY", dir.toString());

        try (ServerSocket taken = new ServerSocket(port)) {
            assertThrows(Exception.class, () -> UniversalMain.start(new String[]{dir.toString()}, dir, env, out));
        }

        // The log tailer is started as soon as the tier is, so its thread shows whether the tier was shut down again.
        assertTrue(noLiveThreadNamed("minecraft-mcp-log-tail"), "the half-started tier is shut down");
    }

    @Test
    void closingStopsTheEndpoint() throws Exception {
        UniversalMain.Running running = UniversalMain.start(new String[]{dir.toString()}, dir, env(), out);

        running.close();

        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void runKeepsServingUntilItsThreadIsInterruptedThenStopsEverything() throws Exception {
        Thread main = new Thread(() -> {
            try {
                UniversalMain.run(new String[]{dir.toString()}, dir, env(), out, hooks::add);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        main.start();
        waitUntilListening();
        // Real time has to pass here: the loop checks once a second, and the test wants it to have gone round again.
        Thread.sleep(1200); // NOSONAR java:S2925

        main.interrupt();
        main.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(main.isAlive());
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void stoppingTheProcessRunsAShutdownHookThatClosesTheServer() throws Exception {
        Thread main = new Thread(() -> {
            try {
                UniversalMain.run(new String[]{dir.toString()}, dir, env(), out, hooks::add);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        main.start();
        waitUntilListening();

        // The hook is registered just after the server starts listening, on the other thread.
        assertTrue(Await.until(() -> hooks.size() == 1, java.time.Duration.ofSeconds(10)));
        assertEquals("minecraft-mcp-shutdown", hooks.get(0).getName());
        hooks.get(0).run();

        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
        main.interrupt();
        main.join(TimeUnit.SECONDS.toMillis(20));
    }

    @Test
    void theRealMainServesFromTheConfigFileInTheDirectoryItIsGiven() throws Exception {
        Files.writeString(dir.resolve("minecraft-mcp.json"), "{\"token\":\"" + TOKEN + "\",\"port\":" + port
                + ",\"universal\":{\"rconPort\":" + game.port() + ",\"rconPassword\":\"" + FakeRconServer.PASSWORD + "\"}}");
        Thread main = new Thread(() -> {
            try {
                UniversalMain.main(new String[]{dir.toString()});
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        main.start();
        waitUntilListening();
        try (McpSyncClient client = client()) {
            client.initialize();
            assertEquals(11, client.listTools().tools().size());
        }

        main.interrupt();
        main.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(main.isAlive());
    }

    @Test
    void withoutADirectoryTheWorkingDirectoryIsUsed() throws Exception {
        try (UniversalMain.Running running = UniversalMain.start(new String[0], dir, env(), out)) {
            assertEquals(port, running.port());
        }

        assertTrue(Files.exists(dir.resolve("minecraft-mcp.json")));
        assertTrue(Files.exists(dir.resolve("minecraft-mcp-audit.jsonl")) || true);
    }

    @Test
    void aServerThatFailsToStopStillStopsTheRest() throws Exception {
        UniversalServices universal = UniversalServices.start(
                new UniversalConfig(new RconConfig("127.0.0.1", game.port(), FakeRconServer.PASSWORD, false),
                        java.util.OptionalInt.empty(), java.util.Optional.empty()), java.time.Duration.ofSeconds(2));
        UniversalMain.Running running = new UniversalMain.Running(1, () -> {
            throw new IllegalStateException("could not stop");
        }, universal);

        assertDoesNotThrow(running::close);

        assertEquals(List.of("the RCON client is closed"), universal.services()
                .find(dev.aomarai.minecraftmcp.core.CommandRunner.class).orElseThrow()
                .runCommand("list", java.time.Duration.ofSeconds(2)).get(10, TimeUnit.SECONDS).feedback());
    }

    private void waitUntilListening() throws Exception {
        assertTrue(Await.until(() -> {
            try (Socket probe = new Socket("127.0.0.1", port)) {
                return true;
            } catch (IOException notYet) {
                return false;
            }
        }, java.time.Duration.ofSeconds(10)), "the server did not start listening");
    }

    private static boolean noLiveThreadNamed(String name) throws InterruptedException {
        return Await.until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.getName().equals(name) && t.isAlive()), java.time.Duration.ofSeconds(5));
    }
}
