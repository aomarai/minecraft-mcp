package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class McpCoreTest {

    private static McpSyncClient client(McpCore core) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + core.port())
                .endpoint(McpCore.DEFAULT_ENDPOINT)
                .build();
        return McpClient.sync(transport).build();
    }

    @Test
    void initializesAndListsToolsOverStreamableHttp() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();

            try (McpSyncClient client = client(core)) {
                McpSchema.InitializeResult init = client.initialize();
                assertNotNull(init.protocolVersion());
                assertEquals("minecraft-mcp", init.serverInfo().name());
                assertEquals("0.1.0", init.serverInfo().version());
                assertNotNull(init.capabilities().tools());
                assertNotEquals(Boolean.TRUE, init.capabilities().tools().listChanged());

                McpSchema.ListToolsResult tools = client.listTools();
                assertNotNull(tools.tools());
                assertTrue(tools.tools().isEmpty(), "catalog starts empty");
            }
        }
    }

    @Test
    void defaultConstructorBindsLoopbackOnEphemeralPort() throws Exception {
        assertEquals("127.0.0.1", McpCore.DEFAULT_HOST);
        assertEquals("/mcp", McpCore.DEFAULT_ENDPOINT);
        try (McpCore core = new McpCore()) {
            core.start();
            assertTrue(core.port() > 0);
            assertNotNull(core.server());
            try (Socket socket = new Socket("127.0.0.1", core.port())) {
                assertTrue(socket.isConnected());
            }
        }
    }

    @Test
    void bindsRequestedHostAndPort() throws Exception {
        int free;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            free = probe.getLocalPort();
        }
        try (McpCore core = new McpCore("127.0.0.1", free)) {
            core.start();
            assertEquals("127.0.0.1", core.host());
            assertEquals(free, core.port());
        }
    }

    @Test
    void closeStopsListening() throws Exception {
        McpCore core = new McpCore("127.0.0.1", 0);
        core.start();
        int port = core.port();
        core.close();
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
    }
}
