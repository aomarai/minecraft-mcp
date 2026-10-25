package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static dev.aomarai.minecraftmcp.core.ToolResults.text;
import static org.junit.jupiter.api.Assertions.*;

class ToolDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeGame game = new FakeGame();
    private final List<AuditEntry> audit = new ArrayList<>();

    private static McpConfig config(boolean writes, boolean admin, int maxVolume, Duration timeout) {
        return new McpConfig("127.0.0.1", 25599, writes, admin, maxVolume, timeout);
    }

    private ToolDispatcher dispatcher(boolean writes, boolean admin) {
        return dispatcher(config(writes, admin, 32768, Duration.ofSeconds(2)), game.services(), audit::add);
    }

    private static ToolDispatcher dispatcher(McpConfig config, GameServices services, AuditLog log) {
        return new ToolDispatcher(config, services, log, CLOCK);
    }

    private static ToolDescriptor tool(String name) {
        return ToolCatalog.defaults().tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> blocks(Object volumeMax, Object limit) {
        return args("dimension", "minecraft:overworld", "min_x", 0, "min_y", 0, "min_z", 0,
                "max_x", volumeMax, "max_y", 0, "max_z", 0, "limit", limit);
    }

    private void assertAudit(int index, String phase, String tool, String decision, String detail) {
        AuditEntry entry = audit.get(index);
        assertEquals(NOW, entry.time());
        assertEquals(phase, entry.phase());
        assertEquals(tool, entry.tool());
        assertEquals(decision, entry.decision());
        assertEquals(detail, entry.detail());
    }

    // ---- observe tools ----

    @Test
    void serverInfoIsAlwaysAllowedAndAudited() {
        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("server_info"), null);

        assertFalse(result.isError());
        assertEquals("Fake 1.0 / Minecraft test / server", text(result));
        assertEquals(1, game.infoCalls);
        assertEquals(2, audit.size());
        assertAudit(0, "decision", "server_info", "allow", "");
        assertAudit(1, "result", "server_info", "allow", "ok: Fake 1.0 / Minecraft test / server");
    }

    @Test
    void listPlayersReturnsEveryField() {
        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("list_players"), Map.of());

        assertFalse(result.isError());
        assertEquals("{\"players\":[{\"name\":\"Steve\",\"uuid\":\"uuid-1\",\"dimension\":\"minecraft:overworld\","
                + "\"x\":1.5,\"y\":64.0,\"z\":-2.5,\"op\":true}]}", text(result));
    }

    @Test
    void listPlayersOnAnEmptyServerIsAnEmptyList() {
        game.players = List.of();

        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("list_players"), Map.of());

        assertFalse(result.isError());
        assertEquals("{\"players\":[]}", text(result));
    }

    @Test
    void queryBlocksPassesTheQueryAndReturnsBlocks() {
        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("query_blocks"), blocks(1, 8));

        assertFalse(result.isError());
        assertEquals("{\"dimension\":\"minecraft:overworld\",\"truncated\":true,\"blocks\":"
                + "[{\"x\":1,\"y\":2,\"z\":3,\"id\":\"minecraft:stone\"}]}", text(result));
        assertEquals(new BlockQuery("minecraft:overworld", 0, 0, 0, 1, 0, 0, 8), game.queries.get(0));
    }

    @Test
    void queryBlocksAcceptsLongAndRejectsBadArguments() {
        ToolDispatcher d = dispatcher(false, false);
        assertFalse(d.call(tool("query_blocks"), blocks(1L, 8L)).isError());

        assertEquals("max_x must be an integer", text(d.call(tool("query_blocks"), blocks("1", 8))));
        assertEquals("max_x must be an integer", text(d.call(tool("query_blocks"), blocks(1.5, 8))));
        assertEquals("max_x must be an integer", text(d.call(tool("query_blocks"), blocks(3_000_000_000L, 8))));
        assertEquals("max_x must be an integer", text(d.call(tool("query_blocks"), blocks(null, 8))));
        assertEquals("max_x must be an integer", text(d.call(tool("query_blocks"), blocks(true, 8))));
        Map<String, Object> noDimension = blocks(1, 8);
        noDimension.put("dimension", 5);
        assertEquals("dimension must be a string", text(d.call(tool("query_blocks"), noDimension)));
        assertEquals("limit must be between 1 and 4096", text(d.call(tool("query_blocks"), blocks(1, 0))));
        assertEquals("max coordinates must be >= min coordinates", text(d.call(tool("query_blocks"), blocks(-1, 8))));
        assertEquals(1, game.queries.size(), "only the valid call reached the game");
    }

    @Test
    void queryBlocksEnforcesTheVolumeCapAtTheBoundary() {
        ToolDispatcher d = dispatcher(config(false, false, 4, Duration.ofSeconds(2)), game.services(), audit::add);

        assertFalse(d.call(tool("query_blocks"), blocks(3, 8)).isError(), "volume 4 == cap is allowed");
        McpSchema.CallToolResult over = d.call(tool("query_blocks"), blocks(4, 8));
        assertTrue(over.isError());
        assertEquals("volume 5 exceeds maxBlockVolume 4", text(over));
        assertEquals(1, game.queries.size());
    }

    // ---- act: run_command ----

    @Test
    void runCommandIsDeniedWhenWritesAreDisabledAndNeverReachesTheGame() {
        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("run_command"), args("command", "say hi"));

        assertTrue(result.isError());
        assertEquals("write access is disabled", text(result));
        assertEquals(0, game.totalCalls());
        assertEquals(1, audit.size(), "a denied call is audited once and has no result entry");
        assertAudit(0, "decision", "run_command", "deny", "write access is disabled");
    }

    @Test
    void runCommandRunsWhenWritesAreEnabled() {
        McpSchema.CallToolResult result = dispatcher(true, false).call(tool("run_command"), args("command", "/say hi"));

        assertFalse(result.isError());
        assertEquals("done", text(result));
        assertEquals(List.of("/say hi"), game.commands);
        assertEquals(Duration.ofSeconds(2), game.commandTimeouts.get(0));
        assertAudit(0, "decision", "run_command", "allow", "");
        assertAudit(1, "result", "run_command", "allow", "ok: done");
    }

    @Test
    void runCommandRefusesDestructiveCommandsEvenWithAdmin() {
        McpSchema.CallToolResult result = dispatcher(true, true).call(tool("run_command"), args("command", "op Steve"));

        assertTrue(result.isError());
        assertEquals("this command needs admin access; use run_admin_command", text(result));
        assertEquals(0, game.totalCalls());
        assertAudit(0, "decision", "run_command", "deny", "this command needs admin access; use run_admin_command");
    }

    @Test
    void anUnrecognisedCommandIsNotAPlainWriteEvenWithAdmin() {
        for (String command : new String[]{"mymod:wipe_world", "execute run say hi", "frobnicate"}) {
            McpSchema.CallToolResult result = dispatcher(true, true).call(tool("run_command"), args("command", command));
            assertTrue(result.isError(), command);
            assertEquals("this command needs admin access; use run_admin_command", text(result), command);
        }
        assertEquals(0, game.totalCalls());
    }

    @Test
    void anUnrecognisedCommandNeedsAdminAndConfirmationThroughTheAdminTool() {
        ToolDispatcher d = dispatcher(true, true);

        McpSchema.CallToolResult pending = d.call(tool("run_admin_command"),
                args("command", "mymod:wipe_world", "confirm", false));
        assertEquals("admin action requires confirmation; call again with confirm=true", text(pending));
        assertEquals(0, game.totalCalls());

        assertFalse(d.call(tool("run_admin_command"), args("command", "mymod:wipe_world", "confirm", true)).isError());
        assertEquals(List.of("mymod:wipe_world"), game.commands);
    }

    @Test
    void anOperatorCanTrustAModdedCommandForPlainWrites() {
        McpConfig config = new McpConfig("127.0.0.1", 25599, true, false, 32768, Duration.ofSeconds(2),
                java.util.Set.of("MyMod:Status"), java.util.Set.of());
        ToolDispatcher d = dispatcher(config, game.services(), audit::add);

        assertFalse(d.call(tool("run_command"), args("command", "mymod:status")).isError());
        assertEquals(List.of("mymod:status"), game.commands);
        assertEquals("admin commands are disabled", text(d.call(tool("run_command"), args("command", "kill @e"))),
                "trusting one command does not loosen the destructive ones");
    }

    @Test
    void runCommandReportsAdminDisabledForDestructiveCommands() {
        McpSchema.CallToolResult result = dispatcher(true, false).call(tool("run_command"), args("command", "kill @e"));

        assertEquals("admin commands are disabled", text(result));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void emptyOrNonStringCommandsAreDenied() {
        ToolDispatcher d = dispatcher(true, true);
        assertEquals("empty command", text(d.call(tool("run_command"), args("command", "  "))));
        assertEquals("empty command", text(d.call(tool("run_command"), args("command", 5))));
        assertEquals("empty command", text(d.call(tool("run_command"), Map.of())));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void failedCommandsAreReportedAsErrorsAndAudited() {
        game.commandResult = CommandResult.error("no such command");

        McpSchema.CallToolResult result = dispatcher(true, false).call(tool("run_command"), args("command", "say nope"));

        assertTrue(result.isError());
        assertEquals("no such command", text(result));
        assertAudit(1, "result", "run_command", "allow", "error: no such command");
    }

    @Test
    void multipleFeedbackLinesAreJoined() {
        game.commandResult = new CommandResult(false, "", List.of("a", "b"));
        assertEquals("a; b", text(dispatcher(true, false).call(tool("run_command"), args("command", "say x"))));
    }

    // ---- admin: run_admin_command ----

    @Test
    void adminToolIsDeniedWhenAdminIsDisabled() {
        McpSchema.CallToolResult result = dispatcher(true, false)
                .call(tool("run_admin_command"), args("command", "op Steve", "confirm", true));

        assertEquals("admin access is disabled", text(result));
        assertEquals(0, game.totalCalls());
        assertAudit(0, "decision", "run_admin_command", "deny", "admin access is disabled");
    }

    @Test
    void destructiveCommandNeedsConfirmationEvenWhenAdminIsEnabled() {
        McpSchema.CallToolResult result = dispatcher(false, true)
                .call(tool("run_admin_command"), args("command", "op Steve", "confirm", false));

        assertTrue(result.isError());
        assertEquals("admin action requires confirmation; call again with confirm=true", text(result));
        assertEquals(0, game.totalCalls());
        assertEquals(1, audit.size());
        assertAudit(0, "decision", "run_admin_command", "confirm", "admin action requires confirmation");
    }

    @Test
    void missingOrNonBooleanConfirmCountsAsNotConfirmed() {
        ToolDispatcher d = dispatcher(false, true);
        assertTrue(text(d.call(tool("run_admin_command"), args("command", "op Steve"))).contains("confirm=true"));
        assertTrue(text(d.call(tool("run_admin_command"), args("command", "op Steve", "confirm", "true")))
                .contains("confirm=true"));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void confirmedDestructiveCommandRuns() {
        McpSchema.CallToolResult result = dispatcher(false, true)
                .call(tool("run_admin_command"), args("command", "op Steve", "confirm", true));

        assertFalse(result.isError());
        assertEquals(List.of("op Steve"), game.commands);
        assertAudit(0, "decision", "run_admin_command", "allow", "");
        assertAudit(1, "result", "run_admin_command", "allow", "ok: done");
    }

    @Test
    void nonDestructiveCommandThroughAdminToolStillNeedsConfirmationAndWrites() {
        ToolDispatcher withWrites = dispatcher(true, true);
        McpSchema.CallToolResult pending = withWrites
                .call(tool("run_admin_command"), args("command", "say hi", "confirm", false));
        assertEquals("admin action requires confirmation; call again with confirm=true", text(pending));
        assertFalse(withWrites.call(tool("run_admin_command"), args("command", "say hi", "confirm", true)).isError());

        McpSchema.CallToolResult unconfirmedNoWrites = dispatcher(false, true)
                .call(tool("run_admin_command"), args("command", "say hi", "confirm", false));
        assertEquals("write commands are disabled", text(unconfirmedNoWrites), "deny wins over confirm");

        game.commands.clear();
        McpSchema.CallToolResult noWrites = dispatcher(false, true)
                .call(tool("run_admin_command"), args("command", "say hi", "confirm", true));
        assertEquals("write commands are disabled", text(noWrites));
        assertEquals(0, game.totalCalls());
    }

    // ---- failures and edge cases ----

    @Test
    void unsupportedPortsAreReportedWithoutThrowing() {
        ToolDispatcher d = dispatcher(config(true, true, 32768, Duration.ofSeconds(2)), GameServices.none(), audit::add);

        assertEquals("runtime info is not supported by this adapter", text(d.call(tool("server_info"), null)));
        assertEquals("player listing is not supported by this adapter", text(d.call(tool("list_players"), null)));
        assertEquals("block queries is not supported by this adapter", text(d.call(tool("query_blocks"), blocks(1, 8))));
        assertEquals("command execution is not supported by this adapter",
                text(d.call(tool("run_command"), args("command", "say hi"))));
        assertAudit(1, "result", "server_info", "allow", "error: runtime info is not supported by this adapter");
    }

    @Test
    void toolsWithoutAHandlerAreReported() {
        ToolDescriptor custom = new ToolDescriptor("custom_tool", "Custom", "A custom tool.",
                ToolPolicy.Scope.OBSERVE, Capability.WORLD, Side.SERVER,
                McpSchema.JsonSchema.builder().type("object").build());

        McpSchema.CallToolResult result = dispatcher(false, false).call(custom, Map.of());

        assertTrue(result.isError());
        assertEquals("custom_tool has no handler", text(result));
    }

    @Test
    void aFutureThatNeverCompletesTimesOutWithinTheBudgetAndIsCancelled() {
        game.pendingCommand = new CompletableFuture<>();
        ToolDispatcher d = dispatcher(config(true, false, 32768, Duration.ofMillis(100)), game.services(), audit::add);

        long start = System.nanoTime();
        McpSchema.CallToolResult result = d.call(tool("run_command"), args("command", "say hi"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isError());
        String expected = "timed out after 100 ms; the call may still have taken effect";
        assertEquals(expected, text(result));
        assertTrue(elapsedMillis < 5_000, "returned within a bounded time: " + elapsedMillis);
        assertTrue(game.pendingCommand.isCancelled());
        assertEquals(Duration.ofMillis(100), game.commandTimeouts.get(0));
        assertAudit(1, "result", "run_command", "allow", "error: " + expected);
    }

    @Test
    void readsThatTimeOutDoNotClaimASideEffectAndAreCancelled() {
        game.pendingPlayers = new CompletableFuture<>();
        game.pendingSnapshot = new CompletableFuture<>();
        ToolDispatcher d = dispatcher(config(false, false, 32768, Duration.ofMillis(100)), game.services(), audit::add);

        assertEquals("timed out after 100 ms", text(d.call(tool("list_players"), Map.of())));
        assertEquals("timed out after 100 ms", text(d.call(tool("query_blocks"), blocks(1, 8))));
        assertTrue(game.pendingPlayers.isCancelled());
        assertTrue(game.pendingSnapshot.isCancelled());
    }

    @Test
    void anExceptionallyCompletedFutureIsReportedWithItsMessage() {
        game.pendingCommand = CompletableFuture.failedFuture(new IllegalStateException("main thread busy"));
        assertEquals("main thread busy", text(dispatcher(true, false).call(tool("run_command"), args("command", "say x"))));
    }

    @Test
    void anExceptionWithoutAMessageIsReportedByClassName() {
        game.pendingCommand = CompletableFuture.failedFuture(new IllegalStateException());
        assertEquals("IllegalStateException", text(dispatcher(true, false).call(tool("run_command"), args("command", "say x"))));
    }

    @Test
    void aCancelledFutureIsReported() {
        CompletableFuture<CommandResult> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        game.pendingCommand = cancelled;
        McpSchema.CallToolResult result = dispatcher(true, false).call(tool("run_command"), args("command", "say x"));
        assertTrue(result.isError());
        assertEquals("CancellationException", text(result));
    }

    @Test
    void interruptionCancelsTheFutureAndRestoresTheInterruptFlag() throws Exception {
        game.pendingCommand = new CompletableFuture<>();
        ToolDispatcher d = dispatcher(config(true, false, 32768, Duration.ofSeconds(30)), game.services(), audit::add);
        AtomicReference<McpSchema.CallToolResult> result = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            result.set(d.call(tool("run_command"), args("command", "say hi")));
            flag.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        while (game.commands.isEmpty()) {
            Thread.sleep(5);
        }
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive());
        assertTrue(result.get().isError());
        assertTrue(game.pendingCommand.isCancelled());
        assertEquals(Boolean.TRUE, flag.get());
    }

    // ---- audit failures ----

    @Test
    void aCallThatCannotBeAuditedIsRefusedAndNeverRuns() {
        AuditLog broken = entry -> {
            throw new IllegalStateException("disk full");
        };
        ToolDispatcher d = dispatcher(config(true, false, 32768, Duration.ofSeconds(2)), game.services(), broken);

        McpSchema.CallToolResult result = d.call(tool("run_command"), args("command", "say hi"));

        assertTrue(result.isError());
        assertEquals("audit log unavailable; call refused", text(result));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void aFailureToAuditTheResultIsReportedWithTheOutcome() {
        List<AuditEntry> seen = new ArrayList<>();
        AuditLog failsOnResult = entry -> {
            seen.add(entry);
            if (entry.phase().equals(AuditEntry.RESULT)) {
                throw new IllegalStateException("disk full");
            }
        };
        ToolDispatcher d = dispatcher(config(true, false, 32768, Duration.ofSeconds(2)), game.services(), failsOnResult);

        McpSchema.CallToolResult result = d.call(tool("run_command"), args("command", "say hi"));

        assertTrue(result.isError());
        assertEquals("audit log unavailable after the call ran; result: done", text(result));
        assertEquals(List.of("say hi"), game.commands);
        assertEquals(2, seen.size());
    }

    @Test
    void auditEntriesCarryTheArguments() {
        dispatcher(true, false).call(tool("run_command"), args("command", "say hi"));
        assertEquals(Map.of("command", "say hi"), audit.get(0).arguments());
        assertEquals(Map.of("command", "say hi"), audit.get(1).arguments());
    }

    @Test
    void publicConstructorUsesTheSystemClock() {
        List<AuditEntry> seen = new ArrayList<>();
        new ToolDispatcher(config(false, false, 1, Duration.ofSeconds(1)), game.services(), seen::add)
                .call(tool("server_info"), null);
        Instant time = seen.get(0).time();
        assertTrue(Duration.between(time, Instant.now()).abs().toMinutes() < 5);
    }
}
