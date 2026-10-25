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

    private static GameServices services(Side side, Capability... provided) {
        FakeGame game = new FakeGame();
        java.util.Set<Capability> set = provided.length == 0
                ? java.util.EnumSet.noneOf(Capability.class) : java.util.EnumSet.copyOf(List.of(provided));
        return new GameServices(
                set.contains(Capability.RUNTIME_INFO) ? game : null,
                set.contains(Capability.PLAYERS) ? game : null,
                set.contains(Capability.WORLD) ? game : null,
                set.contains(Capability.COMMANDS) ? game : null,
                side);
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
        assertEquals(5, all.availableFor(services(Side.SERVER, Capability.values())).tools().size());
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
    void gameServicesReportOnlyTheirNonNullPorts() {
        FakeGame game = new FakeGame();
        assertEquals(java.util.Set.of(), GameServices.none().capabilities());
        assertEquals(java.util.Set.of(Capability.RUNTIME_INFO),
                new GameServices(game, null, null, null, Side.SERVER).capabilities());
        assertEquals(java.util.Set.of(Capability.PLAYERS),
                new GameServices(null, game, null, null, Side.SERVER).capabilities());
        assertEquals(java.util.Set.of(Capability.WORLD),
                new GameServices(null, null, game, null, Side.SERVER).capabilities());
        assertEquals(java.util.Set.of(Capability.COMMANDS),
                new GameServices(null, null, null, game, Side.SERVER).capabilities());
        assertThrows(NullPointerException.class, () -> new GameServices(null, null, null, null, null));
    }

    @Test
    void everyDefaultToolDeclaresTheCapabilityItsHandlerNeeds() {
        java.util.Map<String, Capability> expected = java.util.Map.of(
                "server_info", Capability.RUNTIME_INFO,
                "list_players", Capability.PLAYERS,
                "query_blocks", Capability.WORLD,
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
        assertEquals(5, specs.size());
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            McpSchema.CallToolResult result = spec.callHandler()
                    .apply(null, new McpSchema.CallToolRequest(spec.tool().name(), Map.of("k", "v")));
            assertEquals("via " + spec.tool().name(), ToolResults.text(result));
        }
        assertEquals(names(ToolCatalog.defaults().tools()), called);
        assertEquals(Map.of("k", "v"), received.get(0));
    }
}
