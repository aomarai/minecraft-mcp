package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;

/** Helpers for reading tool results in tests. */
final class ToolResults {

    private ToolResults() {
    }

    /** The text of a result's first content item. */
    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}
