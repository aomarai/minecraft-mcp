package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.aomarai.minecraftmcp.core.ToolResults.text;
import static org.junit.jupiter.api.Assertions.*;

class McpServiceTest {

    @TempDir
    Path dir;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Path config(int port) throws IOException {
        Path file = dir.resolve("config").resolve("minecraft-mcp.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"port\": " + port + "}");
        return file;
    }

    @Test
    void startsFromTheConfigAndServesOnlyWhatTheAdapterProvides() throws Exception {
        FakeGame game = new FakeGame();
        GameServices infoOnly = new GameServices(Side.SERVER, (RuntimeInfo) game::describe);
        int port = freePort();
        Path configFile = config(port);

        try (McpService service = McpService.start(configFile, dir.resolve("audit.jsonl"), config -> infoOnly)) {
            assertEquals(port, service.port());
            String token = ConfigLoader.load(configFile).token();
            try (McpSyncClient client = McpTestClients.client(port, token)) {
                client.initialize();
                assertEquals(List.of("server_info"),
                        client.listTools().tools().stream().map(McpSchema.Tool::name).toList());
                McpSchema.CallToolResult info = client.callTool(new McpSchema.CallToolRequest("server_info", Map.of()));
                assertEquals("Fake 1.0 / Minecraft test / server", text(info));
            }
            try (McpSyncClient wrong = McpTestClients.client(port, "not-the-token-not-the-token-1234")) {
                assertThrows(RuntimeException.class, wrong::initialize);
            }
        }
        assertTrue(Files.size(dir.resolve("audit.jsonl")) > 0, "the call was audited");
        assertFalse(Files.readString(dir.resolve("audit.jsonl")).contains(ConfigLoader.load(configFile).token()));
    }

    @Test
    void listeningBeyondThisMachineIsLoggedAsAWarningAndLoopbackIsNot() throws Exception {
        Path file = dir.resolve("config").resolve("minecraft-mcp.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"port\": " + freePort() + ", \"host\": \"0.0.0.0\", \"allowRemote\": true}");
        try (LogCapture logs = new LogCapture();
             McpService ignored = McpService.start(file, dir.resolve("audit.jsonl"), config -> GameServices.none())) {
            assertTrue(logs.lines().stream().anyMatch(line -> line.contains("Listening on") && line.contains("0.0.0.0")
                    && line.contains("plain HTTP") && line.contains("docs/remote-access.md")), logs.lines().toString());
        }

        Files.writeString(file, "{\"port\": " + freePort() + "}");
        try (LogCapture logs = new LogCapture();
             McpService ignored = McpService.start(file, dir.resolve("audit.jsonl"), config -> GameServices.none())) {
            assertTrue(logs.lines().stream().noneMatch(line -> line.contains("plain HTTP")), logs.lines().toString());
        }
    }

    @Test
    void thePortsAreBuiltFromTheLoadedConfig() throws Exception {
        int port = freePort();
        Path file = dir.resolve("config").resolve("minecraft-mcp.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"port\": " + port + ", \"allowWrites\": true, \"maxBlockVolume\": 77}");
        McpConfig[] seen = new McpConfig[1];

        try (McpService service = McpService.start(file, dir.resolve("audit.jsonl"), config -> {
            seen[0] = config;
            return GameServices.none();
        })) {
            assertEquals(port, seen[0].port());
            assertTrue(seen[0].allowWrites());
            assertFalse(seen[0].allowAdmin());
            assertEquals(77, seen[0].maxBlockVolume());
        }
    }

    @Test
    void closeStopsTheEndpoint() throws Exception {
        int port = freePort();
        McpService service = McpService.start(config(port), dir.resolve("audit.jsonl"), config -> GameServices.none());
        service.close();
        assertThrows(java.net.ConnectException.class, () -> connect(port));
    }

    @Test
    void anInvalidConfigFailsWithAClearMessageAndStartsNothing() throws Exception {
        Path file = config(70000);
        var arg = dir.resolve("audit.jsonl");
        ConfigException e = assertThrows(ConfigException.class, () -> McpService.start(file, arg, config -> GameServices.none()));
        assertTrue(e.getMessage().contains("port must be between 1 and 65535"), e.getMessage());
    }

    @Test
    void aFailedStartLeavesNoListenerOnTheFeedAndNoNotifierThread() throws Exception {
        EventBuffer buffer = new EventBuffer(10);
        CountingFeed feed = new CountingFeed(buffer);
        try (ServerSocket taken = new ServerSocket(0)) {
            Path file = config(taken.getLocalPort());

            var arg2 = dir.resolve("audit.jsonl");
            assertThrows(Exception.class, () -> McpService.start(file, arg2, config -> new GameServices(Side.SERVER, feed)));
        }

        assertEquals(0, feed.listeners());
        buffer.publish(EventType.JOIN, "Steve", "");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().equals("minecraft-mcp-events") && thread.isAlive()));
    }

    @Test
    void aPortThatIsInUseFailsAndLeavesNothingRunning() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            int port = taken.getLocalPort();
            Path file = config(port);

            var arg3 = dir.resolve("audit.jsonl");
            assertThrows(Exception.class, () -> McpService.start(file, arg3, config -> GameServices.none()));
        }
    }

    /** Opens and closes a connection to the local port: it only succeeds while something is listening. */
    private static void connect(int port) throws java.io.IOException {
        try (Socket ignored = new Socket("127.0.0.1", port)) {
            ignored.isConnected();
        }
    }
}
