package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.aomarai.minecraftmcp.core.ToolResults.text;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 1 demonstration: a plain JVM process answers initialize and
 * tools/list, runs a read tool, and runs a policy-gated write, all against a
 * fake game, with a JSONL audit trail on disk.
 */
class PolicyGatedServerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path dir;

    private McpCore core(boolean writes, boolean admin, FakeGame game, Path auditFile) {
        McpConfig config = new McpConfig("127.0.0.1", 25599, writes, admin, 32768, Duration.ofSeconds(5));
        ToolDispatcher dispatcher = new ToolDispatcher(config, game.services(),
                new JsonlAuditLog(auditFile, List.of(TOKEN)));
        return new McpCore("127.0.0.1", 0, ToolCatalog.defaults().availableFor(game.services()), dispatcher, TOKEN);
    }

    @Test
    void readWriteAndAdminFlowThroughRealMcpWithAnAuditTrail() throws Exception {
        FakeGame game = new FakeGame();
        Path auditFile = dir.resolve("audit.jsonl");
        try (McpCore core = core(true, true, game, auditFile)) {
            core.start();
            try (McpSyncClient client = McpTestClients.client(core)) {
                client.initialize();
                assertEquals(ToolCatalog.defaults().onSide(Side.SERVER).tools().size(), client.listTools().tools().size());

                // read
                McpSchema.CallToolResult info = client.callTool(new McpSchema.CallToolRequest("server_info", Map.of()));
                assertFalse(info.isError());
                assertEquals("Fake 1.0 / Minecraft test / server", text(info));

                // policy-gated write
                McpSchema.CallToolResult write = client.callTool(
                        new McpSchema.CallToolRequest("run_command", Map.of("command", "say hello " + TOKEN)));
                assertFalse(write.isError());
                assertEquals(List.of("say hello " + TOKEN), game.commands);

                // destructive command through the ordinary write tool is refused
                McpSchema.CallToolResult refused = client.callTool(
                        new McpSchema.CallToolRequest("run_command", Map.of("command", "stop")));
                assertTrue(refused.isError());
                assertEquals(1, game.commands.size(), "the refused command never reached the game");

                // destructive command needs confirmation, then runs
                McpSchema.CallToolResult unconfirmed = client.callTool(new McpSchema.CallToolRequest(
                        "run_admin_command", Map.of("command", "stop", "confirm", false)));
                assertTrue(unconfirmed.isError());
                assertEquals(1, game.commands.size());
                McpSchema.CallToolResult confirmed = client.callTool(new McpSchema.CallToolRequest(
                        "run_admin_command", Map.of("command", "stop", "confirm", true)));
                assertFalse(confirmed.isError());
                assertEquals(List.of("say hello " + TOKEN, "stop"), game.commands);
            }
        }

        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(auditFile)) {
            entries.add(McpJsonDefaults.getMapper().readValue(line, new TypeRef<Map<String, Object>>() {
            }));
        }
        assertFalse(Files.readString(auditFile).contains(TOKEN), "the token is redacted from the audit log");
        // server_info: 2, run_command ok: 2, run_command refused: 1, admin unconfirmed: 1, admin confirmed: 2
        assertEquals(8, entries.size());
        assertEquals("deny", entries.get(4).get("decision"));
        assertEquals("confirm", entries.get(5).get("decision"));
        assertEquals("allow", entries.get(6).get("decision"));
    }

    @Test
    void defaultsDenyEveryWriteOverRealMcp() throws Exception {
        FakeGame game = new FakeGame();
        Path auditFile = dir.resolve("audit.jsonl");
        try (McpCore core = core(false, false, game, auditFile)) {
            core.start();
            try (McpSyncClient client = McpTestClients.client(core)) {
                client.initialize();
                McpSchema.CallToolResult write = client.callTool(
                        new McpSchema.CallToolRequest("run_command", Map.of("command", "say hi")));
                assertTrue(write.isError());
                assertEquals("write access is disabled", text(write));
                assertEquals(0, game.totalCalls());

                McpSchema.CallToolResult players = client.callTool(new McpSchema.CallToolRequest("list_players", Map.of()));
                assertFalse(players.isError(), "reads work with the secure defaults");
            }
        }
        assertEquals(3, Files.readAllLines(auditFile).size());
    }
}
