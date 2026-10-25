package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpControllerTest {

    /** Every default tool, since the fake game provides every port. */
    private static final int TOOLS = ToolCatalog.defaults().tools().size();

    @TempDir
    Path dir;

    private Path configFile;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void writeConfig(String json) throws IOException {
        configFile = dir.resolve("config").resolve("minecraft-mcp.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, json);
    }

    private McpController controller(Map<String, String> env) {
        return new McpController(configFile, dir.resolve("audit.jsonl"), config -> new FakeGame().services(), env);
    }

    private String token() throws IOException {
        return ConfigLoader.load(configFile, Map.of()).token();
    }

    private static boolean canInitialize(int port, String token) {
        try (McpSyncClient client = McpTestClients.client(port, token)) {
            client.initialize();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    // ---- status ----

    @Test
    void statusIsEmptyUntilStartedAndAfterClose() throws Exception {
        writeConfig("{\"port\": " + freePort() + "}");
        McpController controller = controller(Map.of());
        assertTrue(controller.status().isEmpty());

        controller.start();
        assertTrue(controller.status().isPresent());

        controller.close();
        assertTrue(controller.status().isEmpty());
    }

    @Test
    void statusReportsTheEndpointAndAccessWithoutTheToken() throws Exception {
        int port = freePort();
        writeConfig("{\"port\": " + port + ", \"host\": \"127.0.0.1\"}");
        try (McpController controller = controller(Map.of())) {
            controller.start();

            McpService.Status status = controller.status().orElseThrow();

            assertEquals(new McpService.Status("127.0.0.1", port, false, false, TOOLS), status);
            assertEquals("listening on 127.0.0.1:" + port + ", read-only, " + TOOLS + " tools", status.summary());
            assertFalse(status.toString().contains(token()));
            assertFalse(status.summary().contains(token()));
        }
    }

    @Test
    void theSummaryNamesTheAccessLevel() {
        assertEquals("listening on h:1, read-only, 2 tools", new McpService.Status("h", 1, false, false, 2).summary());
        assertEquals("listening on h:1, writes enabled, 2 tools", new McpService.Status("h", 1, true, false, 2).summary());
        assertEquals("listening on h:1, writes and admin enabled, 2 tools", new McpService.Status("h", 1, true, true, 2).summary());
        assertEquals("listening on h:1, writes and admin enabled, 2 tools", new McpService.Status("h", 1, false, true, 2).summary());
    }

    // ---- reload ----

    @Test
    void reloadAppliesTheNewConfiguration() throws Exception {
        int first = freePort();
        writeConfig("{\"port\": " + first + "}");
        try (McpController controller = controller(Map.of())) {
            controller.start();
            assertFalse(controller.status().orElseThrow().allowWrites());
            assertTrue(canInitialize(first, token()));

            int second = freePort();
            Files.writeString(configFile, "{\"port\": " + second + ", \"allowWrites\": true, \"token\": \"" + token() + "\"}");
            McpService.Status status = controller.reload();

            assertEquals(second, status.port());
            assertTrue(status.allowWrites());
            assertEquals(status, controller.status().orElseThrow());
            assertFalse(listening(first), "the old port is released");
            assertTrue(canInitialize(second, token()));
        }
    }

    @Test
    void anInvalidConfigurationLeavesTheRunningServerUntouched() throws Exception {
        int port = freePort();
        writeConfig("{\"port\": " + port + "}");
        try (McpController controller = controller(Map.of())) {
            controller.start();
            String token = token();
            McpService.Status before = controller.status().orElseThrow();

            Files.writeString(configFile, "{\"port\": 99999999, \"token\": \"" + token + "\"}");
            ConfigException e = assertThrows(ConfigException.class, controller::reload);

            assertTrue(e.getMessage().contains("port"), e.getMessage());
            assertEquals(before, controller.status().orElseThrow());
            assertTrue(canInitialize(port, token), "the server keeps serving with the old settings");
        }
    }

    @Test
    void aPortThatCannotBeBoundLeavesTheServerStopped() throws Exception {
        writeConfig("{\"port\": " + freePort() + "}");
        try (McpController controller = controller(Map.of()); ServerSocket taken = new ServerSocket(0)) {
            controller.start();
            Files.writeString(configFile, "{\"port\": " + taken.getLocalPort() + ", \"token\": \"" + token() + "\"}");

            assertThrows(Exception.class, controller::reload);

            assertTrue(controller.status().isEmpty(), "reported as not running rather than pretending");
        }
    }

    @Test
    void reloadWorksWhenNothingIsRunningYet() throws Exception {
        int port = freePort();
        writeConfig("{\"port\": " + port + "}");
        try (McpController controller = controller(Map.of())) {
            assertEquals(port, controller.reload().port());
        }
    }

    // ---- token rotation ----

    @Test
    void rotationInvalidatesTheOldTokenAndKeepsEverythingElse() throws Exception {
        int port = freePort();
        writeConfig("{\"port\": " + port + ", \"allowWrites\": true, \"maxBlockVolume\": 100}");
        try (McpController controller = controller(Map.of())) {
            controller.start();
            String old = token();
            assertTrue(canInitialize(port, old));

            controller.rotateToken();
            String fresh = token();

            assertNotEquals(old, fresh);
            assertTrue(fresh.length() >= ConfigLoader.MIN_TOKEN_LENGTH);
            assertFalse(canInitialize(port, old), "the old token stops working immediately");
            assertTrue(canInitialize(port, fresh), "the new token works after the restart");
            McpConfig config = ConfigLoader.load(configFile, Map.of()).config();
            assertEquals(port, config.port());
            assertTrue(config.allowWrites());
            assertEquals(100, config.maxBlockVolume());
            assertTrue(controller.status().isPresent());
        }
    }

    @Test
    void rotationNeverPutsTheTokenInTheStatusOrTheAuditLog() throws Exception {
        writeConfig("{\"port\": " + freePort() + "}");
        try (McpController controller = controller(Map.of())) {
            controller.start();
            controller.rotateToken();
            assertFalse(controller.status().orElseThrow().summary().contains(token()));
        }
    }

    @Test
    void aTokenFromTheEnvironmentCannotBeRotatedFromHere() throws Exception {
        int port = freePort();
        writeConfig("{\"port\": " + port + ", \"token\": \"from-the-file-from-the-file-123\"}");
        String envToken = "from-the-env-from-the-env-1234";
        try (McpController controller = controller(Map.of("MINECRAFT_MCP_TOKEN", envToken))) {
            controller.start();

            ConfigException e = assertThrows(ConfigException.class, controller::rotateToken);

            assertEquals("MINECRAFT_MCP_TOKEN is set, so it supplies the token; change that instead", e.getMessage());
            assertTrue(Files.readString(configFile).contains("from-the-file-from-the-file-123"), "the file is untouched");
            assertTrue(canInitialize(port, envToken), "still serving");
        }
    }
}
