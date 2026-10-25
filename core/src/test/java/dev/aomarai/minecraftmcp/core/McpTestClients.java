package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

/** Builds MCP clients that present the core's bearer token. */
final class McpTestClients {

    private McpTestClients() {
    }

    static McpSyncClient client(McpCore core) {
        return client(core, core.token());
    }

    static McpSyncClient client(McpCore core, String token) {
        return client(core.port(), token);
    }

    static McpSyncClient client(int port, String token) {
        return McpClient.sync(HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint(McpCore.DEFAULT_ENDPOINT)
                .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build()).build();
    }
}
