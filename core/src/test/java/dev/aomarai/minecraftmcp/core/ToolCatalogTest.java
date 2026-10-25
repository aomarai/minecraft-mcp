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
        assertEquals(List.of("server_info", "list_players", "query_blocks", "list_registry", "list_entities",
                        "get_world_state", "list_permissions", "get_inventory", "read_log"),
                names(catalog.byScope(ToolPolicy.Scope.OBSERVE)));
        assertEquals(List.of("run_command"), names(catalog.byScope(ToolPolicy.Scope.ACT)));
        assertEquals(List.of("run_admin_command"), names(catalog.byScope(ToolPolicy.Scope.ADMIN)));
        assertEquals(ToolCatalog.defaults().tools().size(), catalog.tools().size());
        assertEquals(11, catalog.tools().size(), "update docs/tools.md and the golden files when this changes");
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

    private static GameServices services(Side side, Capability... provided) {
        return new FakeGame().services(side, provided);
    }

    @Test
    void everyCapabilitySubsetAdvertisesExactlyTheToolsItCanServe() {
        ToolCatalog all = ToolCatalog.defaults();
        Capability[] values = Capability.values();
        for (int mask = 0; mask < (1 << values.length); mask++) {
            List<Capability> subset = new java.util.ArrayList<>();
            for (int bit = 0; bit < values.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    subset.add(values[bit]);
                }
            }
            GameServices services = services(Side.SERVER, subset.toArray(new Capability[0]));
            List<String> expected = all.tools().stream()
                    .filter(tool -> subset.contains(tool.capability()))
                    .map(ToolDescriptor::name).toList();
            assertEquals(expected, names(all.availableFor(services).tools()), subset.toString());
        }
    }

    @Test
    void specificAdaptersGetSpecificCatalogs() {
        ToolCatalog all = ToolCatalog.defaults();
        // A protocol-only (RCON-like) adapter: no world reads, no players.
        assertEquals(List.of("server_info", "run_command", "run_admin_command"),
                names(all.availableFor(services(Side.SERVER, Capability.RUNTIME_INFO, Capability.COMMANDS)).tools()));
        assertEquals(List.of(), names(all.availableFor(GameServices.none()).tools()));
        assertEquals(all.tools().size(), all.availableFor(services(Side.SERVER, Capability.values())).tools().size());
    }

    @Test
    void toolsOnTheOtherSideAreNeverAdvertised() {
        ToolCatalog all = ToolCatalog.defaults();
        assertEquals(List.of(), names(all.availableFor(services(Side.CLIENT, Capability.values())).tools()));
        ToolDescriptor clientTool = new ToolDescriptor("client_tool", "C", "A client tool.",
                ToolPolicy.Scope.OBSERVE, Capability.WORLD, Side.CLIENT, EMPTY);
        ToolCatalog mixed = new ToolCatalog(List.of(all.tools().get(0), clientTool));
        assertEquals(List.of("client_tool"),
                names(mixed.availableFor(services(Side.CLIENT, Capability.WORLD)).tools()));
        assertEquals(List.of(),
                names(mixed.availableFor(services(Side.SERVER, Capability.WORLD)).tools()));
    }

    @Test
    void everyDefaultToolDeclaresTheCapabilityItsHandlerNeeds() {
        java.util.Map<String, Capability> expected = java.util.Map.of(
                "server_info", Capability.RUNTIME_INFO,
                "list_players", Capability.PLAYERS,
                "query_blocks", Capability.WORLD,
                "list_registry", Capability.REGISTRIES,
                "list_entities", Capability.ENTITIES,
                "get_world_state", Capability.WORLD_STATE,
                "list_permissions", Capability.PERMISSIONS,
                "get_inventory", Capability.INVENTORY,
                "read_log", Capability.LOGS,
                "run_command", Capability.COMMANDS,
                "run_admin_command", Capability.COMMANDS);
        for (ToolDescriptor tool : ToolCatalog.defaults().tools()) {
            assertEquals(expected.get(tool.name()), tool.capability(), tool.name());
            assertEquals(Side.SERVER, tool.side(), tool.name());
        }
    }

    @Test
    void rejectsDuplicateNames() {
        ToolDescriptor a = new ToolDescriptor("dup", "T", "D", ToolPolicy.Scope.OBSERVE, Capability.WORLD, Side.SERVER, EMPTY);
        assertThrows(IllegalArgumentException.class, () -> new ToolCatalog(List.of(a, a)));
    }

    @Test
    void catalogIsImmutable() {
        ToolCatalog catalog = ToolCatalog.defaults();
        assertThrows(UnsupportedOperationException.class, () -> catalog.tools().clear());
    }

    @Test
    void specsDelegateEveryCallToTheInvoker() {
        List<String> called = new java.util.ArrayList<>();
        List<Map<String, Object>> received = new java.util.ArrayList<>();
        ToolInvoker invoker = (tool, arguments) -> {
            called.add(tool.name());
            received.add(arguments);
            return McpSchema.CallToolResult.builder().addTextContent("via " + tool.name()).build();
        };
        List<McpServerFeatures.SyncToolSpecification> specs = ToolCatalog.defaults().specs(invoker);
        assertEquals(ToolCatalog.defaults().tools().size(), specs.size());
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            McpSchema.CallToolResult result = spec.callHandler()
                    .apply(null, new McpSchema.CallToolRequest(spec.tool().name(), Map.of("k", "v")));
            assertEquals("via " + spec.tool().name(), ToolResults.text(result));
        }
        assertEquals(names(ToolCatalog.defaults().tools()), called);
        assertEquals(Map.of("k", "v"), received.get(0));
    }
}
