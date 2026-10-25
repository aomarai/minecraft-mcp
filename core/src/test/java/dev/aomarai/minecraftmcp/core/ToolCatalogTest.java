package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {

    private static final McpSchema.JsonSchema EMPTY = McpSchema.JsonSchema.builder().type("object").build();

    private static List<String> names(List<ToolDescriptor> tools) {
        return tools.stream().map(ToolDescriptor::name).toList();
    }

    @Test
    void defaultsAreGroupedByScope() {
        ToolCatalog catalog = ToolCatalog.defaults();
        assertEquals(List.of("server_info", "list_players", "query_blocks"),
                names(catalog.byScope(ToolPolicy.Scope.OBSERVE)));
        assertEquals(List.of("run_command"), names(catalog.byScope(ToolPolicy.Scope.ACT)));
        assertEquals(List.of("run_admin_command"), names(catalog.byScope(ToolPolicy.Scope.ADMIN)));
        assertEquals(5, catalog.tools().size());
    }

    @Test
    void everyDefaultToolIsStrictlySchemaed() {
        for (ToolDescriptor tool : ToolCatalog.defaults().tools()) {
            McpSchema.JsonSchema schema = tool.inputSchema();
            assertEquals("object", schema.type(), tool.name());
            assertEquals(Boolean.FALSE, schema.additionalProperties(), tool.name());
            assertNotNull(schema.required(), tool.name());
            assertTrue(schema.properties().keySet().containsAll(schema.required()), tool.name());
        }
    }

    @Test
    void readsAreReadOnlyAndDestructiveToolsAreMarked() {
        for (ToolDescriptor tool : ToolCatalog.defaults().tools()) {
            McpSchema.ToolAnnotations a = tool.toTool().annotations();
            assertEquals(tool.scope() == ToolPolicy.Scope.OBSERVE, a.readOnlyHint(), tool.name());
            assertEquals(tool.scope() == ToolPolicy.Scope.ADMIN, a.destructiveHint(), tool.name());
            assertFalse(a.readOnlyHint() && a.destructiveHint(), tool.name());
        }
    }

    @Test
    void queryBlocksSchemaMirrorsBlockQueryBounds() {
        ToolDescriptor query = ToolCatalog.defaults().tools().stream()
                .filter(t -> t.name().equals("query_blocks")).findFirst().orElseThrow();
        Map<String, Object> props = query.inputSchema().properties();
        @SuppressWarnings("unchecked")
        Map<String, Object> limit = (Map<String, Object>) props.get("limit");
        assertEquals(1, limit.get("minimum"));
        assertEquals(4096, limit.get("maximum"));
        assertEquals(8, query.inputSchema().required().size());
    }

    @Test
    void adminToolRequiresConfirm() {
        ToolDescriptor admin = ToolCatalog.defaults().byScope(ToolPolicy.Scope.ADMIN).get(0);
        assertEquals(List.of("command", "confirm"), admin.inputSchema().required());
    }

    @Test
    void rejectsDuplicateNames() {
        ToolDescriptor a = new ToolDescriptor("dup", "T", "D", ToolPolicy.Scope.OBSERVE, EMPTY);
        assertThrows(IllegalArgumentException.class, () -> new ToolCatalog(List.of(a, a)));
    }

    @Test
    void catalogIsImmutable() {
        ToolCatalog catalog = ToolCatalog.defaults();
        assertThrows(UnsupportedOperationException.class, () -> catalog.tools().clear());
    }

    @Test
    void specsReportNotImplemented() {
        List<McpServerFeatures.SyncToolSpecification> specs = ToolCatalog.defaults().specs();
        assertEquals(5, specs.size());
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            McpSchema.CallToolResult result = spec.callHandler().apply(null, null);
            assertTrue(result.isError(), spec.tool().name());
            assertEquals(spec.tool().name() + " is not implemented yet",
                    ((McpSchema.TextContent) result.content().get(0)).text());
        }
    }
}
