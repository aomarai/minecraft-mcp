package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** The token is enforced on the real server: nothing unauthenticated reaches the MCP transport. */
class McpCoreAuthTest {

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";

    private static HttpResponse<String> post(McpCore core, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port() + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INIT));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestsWithoutTheTokenAreRejectedBeforeTheTransport() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();

            HttpResponse<String> none = post(core, "/mcp", null);
            assertEquals(401, none.statusCode());
            assertEquals("Bearer realm=\"minecraft-mcp\"", none.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertEquals("", none.body(), "no detail about what was wrong");

            assertEquals(401, post(core, "/mcp", "Bearer not-the-token-not-the-token-1234").statusCode());
            assertEquals(401, post(core, "/mcp", "Basic " + core.token()).statusCode());
            assertEquals(401, post(core, "/anything-else", null).statusCode(), "the whole server is protected");
        }
    }

    @Test
    void theRightTokenReachesTheTransport() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();
            HttpResponse<String> ok = post(core, "/mcp", "Bearer " + core.token());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("minecraft-mcp"), ok.body());
        }
    }

    @Test
    void aClientWithTheWrongTokenCannotInitialize() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();
            try (McpSyncClient client = McpTestClients.client(core, "not-the-token-not-the-token-1234")) {
                assertThrows(RuntimeException.class, client::initialize);
            }
            try (McpSyncClient client = McpTestClients.client(core)) {
                assertNotNull(client.initialize());
            }
        }
    }

    @Test
    void foreignOriginsAreRefusedEvenWithTheToken() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port() + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + core.token())
                    .header("Origin", "http://evil.example")
                    .POST(HttpRequest.BodyPublishers.ofString(INIT)).build();
            assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void generatedTokensAreStrongAndDistinctPerCore() throws Exception {
        try (McpCore a = new McpCore("127.0.0.1", 0); McpCore b = new McpCore("127.0.0.1", 0)) {
            assertTrue(a.token().length() >= ConfigLoader.MIN_TOKEN_LENGTH);
            assertNotEquals(a.token(), b.token());
        }
    }

    @Test
    void aCoreCannotBeBuiltWithoutAUsableToken() {
        ToolInvoker invoker = (tool, arguments) -> null;
        var arg = ToolCatalog.defaults();
        assertThrows(IllegalArgumentException.class, () -> new McpCore("127.0.0.1", 0, arg, invoker, "short"));
        var arg2 = ToolCatalog.defaults();
        assertThrows(NullPointerException.class, () -> new McpCore("127.0.0.1", 0, arg2, invoker, null));
    }
}
