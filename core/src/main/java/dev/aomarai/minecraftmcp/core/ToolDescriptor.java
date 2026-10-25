package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Static description of one MCP tool: identity, the scope it needs, and its
 * input schema. Annotations are derived from the scope so they cannot drift
 * from the policy vocabulary in {@link ToolPolicy.Scope}.
 *
 * <p>{@code capability} is the port the tool needs and {@code side} is where it
 * runs; {@link ToolCatalog#availableFor} only keeps tools whose capability the
 * adapter provides on the adapter's side.</p>
 *
 * <p>Naming convention: tool names and argument names are {@code snake_case}
 * ({@code [a-z][a-z0-9_]*}). The constructor rejects anything else.</p>
 *
 * <p>Annotations are hints for MCP hosts. They are not enforcement; the policy
 * gate decides what actually runs.</p>
 */
public record ToolDescriptor(
        String name,
        String title,
        String description,
        ToolPolicy.Scope scope,
        Capability capability,
        Side side,
        McpSchema.JsonSchema inputSchema) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(inputSchema, "inputSchema");
        requireName("tool name", name);
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (inputSchema.properties() != null) {
            inputSchema.properties().keySet().forEach(key -> requireName("argument name", key));
        }
    }

    private static void requireName(String what, String value) {
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must be snake_case: " + value);
        }
    }

    /** Annotation hints derived from the scope. */
    public McpSchema.ToolAnnotations annotations() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(scope == ToolPolicy.Scope.OBSERVE)
                .destructiveHint(scope == ToolPolicy.Scope.ADMIN)
                .idempotentHint(scope == ToolPolicy.Scope.OBSERVE)
                .openWorldHint(false)
                .build();
    }

    public McpSchema.Tool toTool() {
        return McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(inputSchema)
                .annotations(annotations())
                .build();
    }
}
