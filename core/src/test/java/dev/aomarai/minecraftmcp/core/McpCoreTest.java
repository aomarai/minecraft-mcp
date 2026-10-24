package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCoreTest {

    @Test
    void initializesAndListsToolsOverStreamableHttp() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();

            String baseUri = "http://127.0.0.1:" + core.port();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder(baseUri)
                    .endpoint("/mcp")
                    .build();

            try (McpSyncClient client = McpClient.sync(transport).build()) {
                McpSchema.InitializeResult init = client.initialize();
                assertNotNull(init);
                assertNotNull(init.protocolVersion());
                assertNotNull(init.serverInfo());
                assertNotNull(init.capabilities());

                McpSchema.ListToolsResult tools = client.listTools();
                assertNotNull(tools);
                assertNotNull(tools.tools());
                assertTrue(tools.tools().isEmpty(), "catalog starts empty");
            }
        }
    }
}
