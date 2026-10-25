package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpCoreTest {

    private static McpSyncClient client(McpCore core) {
        return McpTestClients.client(core);
    }

    @Test
    void initializesAndListsToolsOverStreamableHttp() throws Exception {
        try (McpCore core = TestCores.full(new FakeGame())) {
            core.start();

            try (McpSyncClient client = McpTestClients.client(core, TestCores.TOKEN)) {
                McpSchema.InitializeResult init = client.initialize();
                assertNotNull(init.protocolVersion());
                assertEquals("minecraft-mcp", init.serverInfo().name());
                assertEquals("0.1.0", init.serverInfo().version());
                assertNotNull(init.capabilities().tools());
                assertNotEquals(Boolean.TRUE, init.capabilities().tools().listChanged());

                McpSchema.ListToolsResult tools = client.listTools();
                assertEquals(
                        List.of("server_info", "list_players", "query_blocks", "run_command", "run_admin_command"),
                        tools.tools().stream().map(McpSchema.Tool::name).toList());

                McpSchema.CallToolResult called = client.callTool(
                        new McpSchema.CallToolRequest("server_info", Map.of()));
                assertFalse(called.isError());
            }
        }
    }

    @Test
    void aCoreWithNoGameAdvertisesNoTools() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();
            try (McpSyncClient client = client(core)) {
                client.initialize();
                assertTrue(client.listTools().tools().isEmpty());
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

    @Test
    void closeShutsTheMcpTransportDownGracefully() throws Exception {
        McpCore core = new McpCore("127.0.0.1", 0);
        core.close();

        int[] status = {0};
        java.lang.reflect.InvocationHandler request = (proxy, method, args) -> switch (method.getName()) {
            case "getRequestURI" -> McpCore.DEFAULT_ENDPOINT;
            case "getMethod" -> "POST";
            default -> null;
        };
        java.lang.reflect.InvocationHandler response = (proxy, method, args) -> {
            if (method.getName().equals("sendError")) {
                status[0] = (Integer) args[0];
            }
            return null;
        };
        ClassLoader loader = McpCoreTest.class.getClassLoader();
        jakarta.servlet.http.HttpServletRequest req = (jakarta.servlet.http.HttpServletRequest)
                java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class}, request);
        jakarta.servlet.http.HttpServletResponse resp = (jakarta.servlet.http.HttpServletResponse)
                java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{jakarta.servlet.http.HttpServletResponse.class}, response);

        core.transport().service(req, resp);
        assertEquals(503, status[0], "a closed core must refuse new MCP requests");
    }
}
