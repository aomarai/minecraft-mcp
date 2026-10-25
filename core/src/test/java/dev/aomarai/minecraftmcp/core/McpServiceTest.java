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
        GameServices infoOnly = new GameServices(game, null, null, null, Side.SERVER);
        int port = freePort();
        Path configFile = config(port);

        try (McpService service = McpService.start(configFile, dir.resolve("audit.jsonl"), infoOnly)) {
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
    void closeStopsTheEndpoint() throws Exception {
        int port = freePort();
        McpService service = McpService.start(config(port), dir.resolve("audit.jsonl"), GameServices.none());
        service.close();
        assertThrows(java.net.ConnectException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void anInvalidConfigFailsWithAClearMessageAndStartsNothing() throws Exception {
        Path file = config(70000);
        ConfigException e = assertThrows(ConfigException.class,
                () -> McpService.start(file, dir.resolve("audit.jsonl"), GameServices.none()));
        assertTrue(e.getMessage().contains("port must be between 1 and 65535"), e.getMessage());
    }

    @Test
    void aPortThatIsInUseFailsAndLeavesNothingRunning() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            int port = taken.getLocalPort();
            Path file = config(port);

            assertThrows(Exception.class, () -> McpService.start(file, dir.resolve("audit.jsonl"), GameServices.none()));
        }
    }
}
