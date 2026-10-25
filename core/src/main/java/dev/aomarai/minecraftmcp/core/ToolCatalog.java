package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The set of tools the server advertises, grouped by {@link ToolPolicy.Scope}.
 *
 * <p>This catalog defines names, schemas and annotations only. Calls are
 * executed by a {@link ToolInvoker}, normally the policy-gated
 * {@link ToolDispatcher}.</p>
 */
public final class ToolCatalog {

    private static final int MAX_QUERY_LIMIT = 4096;

    private final List<ToolDescriptor> tools;

    public ToolCatalog(List<ToolDescriptor> tools) {
        this.tools = List.copyOf(tools);
        Set<String> seen = new LinkedHashSet<>();
        for (ToolDescriptor tool : this.tools) {
            if (!seen.add(tool.name())) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
        }
    }

    /** The v1 catalog. Changing it changes {@code tools/list}; update the golden file. */
    public static ToolCatalog defaults() {
        return new ToolCatalog(List.of(
                new ToolDescriptor("server_info", "Server info",
                        "Describe the game runtime: loader, Minecraft version and side.",
                        ToolPolicy.Scope.OBSERVE, schema(props(), List.of())),
                new ToolDescriptor("list_players", "List players",
                        "List online players with position, dimension and operator status.",
                        ToolPolicy.Scope.OBSERVE, schema(props(), List.of())),
                new ToolDescriptor("query_blocks", "Query blocks",
                        "Read blocks in a bounded box of one dimension. Blocks are identified by registry id.",
                        ToolPolicy.Scope.OBSERVE, schema(
                                props(
                                        "dimension", string("Dimension registry id, e.g. minecraft:overworld."),
                                        "min_x", integer("Lower X bound, inclusive.", null, null),
                                        "min_y", integer("Lower Y bound, inclusive.", null, null),
                                        "min_z", integer("Lower Z bound, inclusive.", null, null),
                                        "max_x", integer("Upper X bound, inclusive.", null, null),
                                        "max_y", integer("Upper Y bound, inclusive.", null, null),
                                        "max_z", integer("Upper Z bound, inclusive.", null, null),
                                        "limit", integer("Maximum blocks to return.", 1, MAX_QUERY_LIMIT)),
                                List.of("dimension", "min_x", "min_y", "min_z", "max_x", "max_y", "max_z", "limit"))),
                new ToolDescriptor("run_command", "Run command",
                        "Run a non-destructive server command through Brigadier. Requires write access.",
                        ToolPolicy.Scope.ACT, schema(
                                props("command", string("Command without the leading slash.")),
                                List.of("command"))),
                new ToolDescriptor("run_admin_command", "Run admin command",
                        "Run a destructive or administrative command. Requires admin access and confirm=true.",
                        ToolPolicy.Scope.ADMIN, schema(
                                props(
                                        "command", string("Command without the leading slash."),
                                        "confirm", bool("Must be true to run the command.")),
                                List.of("command", "confirm")))));
    }

    public List<ToolDescriptor> tools() {
        return tools;
    }

    public List<ToolDescriptor> byScope(ToolPolicy.Scope scope) {
        return tools.stream().filter(tool -> tool.scope() == scope).toList();
    }

    /** SDK registrations whose call handlers delegate to {@code invoker}. */
    public List<McpServerFeatures.SyncToolSpecification> specs(ToolInvoker invoker) {
        return tools.stream()
                .map(tool -> McpServerFeatures.SyncToolSpecification.builder()
                        .tool(tool.toTool())
                        .callHandler((exchange, request) -> invoker.call(tool, request.arguments()))
                        .build())
                .toList();
    }

    private static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required) {
        return McpSchema.JsonSchema.builder()
                .type("object")
                .properties(properties)
                .required(required)
                .additionalProperties(false)
                .build();
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> string(String description) {
        return props("type", "string", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return props("type", "boolean", "description", description);
    }

    private static Map<String, Object> integer(String description, Integer minimum, Integer maximum) {
        Map<String, Object> map = props("type", "integer", "description", description);
        if (minimum != null) {
            map.put("minimum", minimum);
        }
        if (maximum != null) {
            map.put("maximum", maximum);
        }
        return map;
    }
}
