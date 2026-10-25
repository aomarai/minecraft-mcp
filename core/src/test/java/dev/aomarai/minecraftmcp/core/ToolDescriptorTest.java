package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolDescriptorTest {

    private static final McpSchema.JsonSchema EMPTY = McpSchema.JsonSchema.builder()
            .type("object").properties(Map.of()).required(List.of()).additionalProperties(false).build();

    private static ToolDescriptor descriptor(String name, ToolPolicy.Scope scope) {
        return new ToolDescriptor(name, "Title", "Description", scope, EMPTY);
    }

    @Test
    void observeToolsAreReadOnlyAndIdempotent() {
        McpSchema.ToolAnnotations a = descriptor("t", ToolPolicy.Scope.OBSERVE).annotations();
        assertEquals(Boolean.TRUE, a.readOnlyHint());
        assertEquals(Boolean.FALSE, a.destructiveHint());
        assertEquals(Boolean.TRUE, a.idempotentHint());
        assertEquals(Boolean.FALSE, a.openWorldHint());
    }

    @Test
    void actToolsAreWritesButNotDestructive() {
        McpSchema.ToolAnnotations a = descriptor("t", ToolPolicy.Scope.ACT).annotations();
        assertEquals(Boolean.FALSE, a.readOnlyHint());
        assertEquals(Boolean.FALSE, a.destructiveHint());
        assertEquals(Boolean.FALSE, a.idempotentHint());
        assertEquals(Boolean.FALSE, a.openWorldHint());
    }

    @Test
    void adminToolsAreDestructive() {
        McpSchema.ToolAnnotations a = descriptor("t", ToolPolicy.Scope.ADMIN).annotations();
        assertEquals(Boolean.FALSE, a.readOnlyHint());
        assertEquals(Boolean.TRUE, a.destructiveHint());
        assertEquals(Boolean.FALSE, a.idempotentHint());
        assertEquals(Boolean.FALSE, a.openWorldHint());
    }

    @Test
    void toToolCarriesEveryField() {
        ToolDescriptor d = descriptor("do_it", ToolPolicy.Scope.ACT);
        McpSchema.Tool tool = d.toTool();
        assertEquals("do_it", tool.name());
        assertEquals("Title", tool.title());
        assertEquals("Description", tool.description());
        assertEquals("object", tool.inputSchema().get("type"));
        assertEquals(Boolean.FALSE, tool.inputSchema().get("additionalProperties"));
        assertEquals(d.annotations(), tool.annotations());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "kebab-case", "9lives", "_lead", "has space", "camelCase"})
    void rejectsNonSnakeCaseToolNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> descriptor(name, ToolPolicy.Scope.OBSERVE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a1", "snake_case_2"})
    void acceptsSnakeCaseToolNames(String name) {
        assertDoesNotThrow(() -> descriptor(name, ToolPolicy.Scope.OBSERVE));
    }

    @Test
    void rejectsNonSnakeCaseArgumentNames() {
        McpSchema.JsonSchema bad = McpSchema.JsonSchema.builder()
                .type("object").properties(Map.of("minX", Map.of("type", "integer"))).build();
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("t", "T", "D", ToolPolicy.Scope.OBSERVE, bad));
    }

    @Test
    void acceptsSchemaWithoutProperties() {
        McpSchema.JsonSchema none = McpSchema.JsonSchema.builder().type("object").build();
        assertDoesNotThrow(() -> new ToolDescriptor("t", "T", "D", ToolPolicy.Scope.OBSERVE, none));
    }

    @Test
    void rejectsBlankTitleAndDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("t", " ", "D", ToolPolicy.Scope.OBSERVE, EMPTY));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("t", "T", " ", ToolPolicy.Scope.OBSERVE, EMPTY));
    }

    @Test
    void rejectsNulls() {
        ToolPolicy.Scope s = ToolPolicy.Scope.OBSERVE;
        assertThrows(NullPointerException.class, () -> new ToolDescriptor(null, "T", "D", s, EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolDescriptor("t", null, "D", s, EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolDescriptor("t", "T", null, s, EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolDescriptor("t", "T", "D", null, EMPTY));
        assertThrows(NullPointerException.class, () -> new ToolDescriptor("t", "T", "D", s, null));
    }
}
