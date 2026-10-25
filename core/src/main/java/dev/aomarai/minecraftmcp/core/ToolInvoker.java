package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/** Executes one catalog tool call and always returns an MCP result, never throws. */
@FunctionalInterface
public interface ToolInvoker {

    McpSchema.CallToolResult call(ToolDescriptor tool, Map<String, Object> arguments);
}
