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

    /** The call succeeded and returned what the fake game answered. */
    private static void assertDone(McpSchema.CallToolResult result) {
        assertFalse(result.isError(), text(result));
        assertEquals("done", text(result));
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

    // ---- registries and entities ----

    private static Map<String, Object> entityArgs(Object... overrides) {
        Map<String, Object> map = args("dimension", "minecraft:overworld", "x", 1, "y", 64, "z", -2, "radius", 8);
        for (int i = 0; i < overrides.length; i += 2) {
            map.put((String) overrides[i], overrides[i + 1]);
        }
        return map;
    }

    @Test
    void listRegistryReturnsAPageWithPagingInformation() {
        McpSchema.CallToolResult result = dispatcher(false, false)
                .call(tool("list_registry"), args("registry", "minecraft:block", "search", "stone", "offset", 2, "limit", 5));

        assertFalse(result.isError());
        assertEquals("{\"registry\":\"minecraft:block\",\"total\":7,\"offset\":2,\"hasMore\":true,"
                + "\"ids\":[\"minecraft:stone\",\"create:cogwheel\"]}", text(result));
        assertEquals(new RegistryQuery("minecraft:block", "stone", 2, 5), game.registryQueries.get(0));
    }

    @Test
    void listRegistryDefaultsToTheFirstFiftyAndNormalizesTheId() {
        dispatcher(false, false).call(tool("list_registry"), args("registry", "item"));

        assertEquals(new RegistryQuery("minecraft:item", "", 0, 50), game.registryQueries.get(0));
    }

    @Test
    void listRegistryRejectsBadArgumentsBeforeTheGameIsAsked() {
        ToolDispatcher d = dispatcher(false, false);
        assertTrue(text(d.call(tool("list_registry"), args("registry", "Not Valid"))).startsWith("registry must be a resource location"));
        assertEquals("registry must be a string", text(d.call(tool("list_registry"), args())));
        assertEquals("limit must be between 1 and 200", text(d.call(tool("list_registry"), args("registry", "block", "limit", 201))));
        assertEquals("offset must not be negative", text(d.call(tool("list_registry"), args("registry", "block", "offset", -1))));
        assertEquals("search must be a string", text(d.call(tool("list_registry"), args("registry", "block", "search", 5))));
        assertEquals("limit must be an integer", text(d.call(tool("list_registry"), args("registry", "block", "limit", 1.5))));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void listEntitiesReturnsEveryFieldAndPassesTheQuery() {
        McpSchema.CallToolResult result = dispatcher(false, false)
                .call(tool("list_entities"), entityArgs("type", "zombie", "limit", 3));

        assertFalse(result.isError());
        assertEquals("{\"dimension\":\"minecraft:overworld\",\"truncated\":true,\"entities\":["
                + "{\"type\":\"minecraft:zombie\",\"uuid\":\"uuid-2\",\"name\":\"Zombie\",\"x\":1.5,\"y\":64.0,\"z\":-2.5}]}",
                text(result));
        assertEquals(new EntityQuery("minecraft:overworld", 1, 64, -2, 8, "minecraft:zombie", 3), game.entityQueries.get(0));
    }

    @Test
    void listEntitiesDefaultsToAnyTypeAndFiftyResults() {
        dispatcher(false, false).call(tool("list_entities"), entityArgs());

        assertEquals(new EntityQuery("minecraft:overworld", 1, 64, -2, 8, "", 50), game.entityQueries.get(0));
    }

    @Test
    void listEntitiesRejectsBadArgumentsBeforeTheGameIsAsked() {
        ToolDispatcher d = dispatcher(false, false);
        assertEquals("radius must be between 1 and 64", text(d.call(tool("list_entities"), entityArgs("radius", 65))));
        assertEquals("radius must be between 1 and 64", text(d.call(tool("list_entities"), entityArgs("radius", 0.5))));
        assertEquals("x must be a number", text(d.call(tool("list_entities"), entityArgs("x", "1"))));
        assertEquals("y must be a number", text(d.call(tool("list_entities"), entityArgs("y", Double.NaN))));
        assertEquals("dimension must be a string", text(d.call(tool("list_entities"), entityArgs("dimension", 1))));
        assertTrue(text(d.call(tool("list_entities"), entityArgs("type", "Zombie!"))).startsWith("type must be a resource location"));
        assertEquals("limit must be between 1 and 200", text(d.call(tool("list_entities"), entityArgs("limit", 0))));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void registryAndEntityReadsThatTimeOutAreCancelledWithoutClaimingASideEffect() {
        game.pendingRegistry = new CompletableFuture<>();
        game.pendingEntities = new CompletableFuture<>();
        ToolDispatcher d = dispatcher(config(false, false, 32768, Duration.ofMillis(100)), game.services(), audit::add);

        assertEquals("timed out after 100 ms", text(d.call(tool("list_registry"), args("registry", "block"))));
        assertEquals("timed out after 100 ms", text(d.call(tool("list_entities"), entityArgs())));
        assertTrue(game.pendingRegistry.isCancelled());
        assertTrue(game.pendingEntities.isCancelled());
    }

    // ---- world state and permissions ----

    @Test
    void getWorldStateReturnsTimeWeatherDifficultyAndSortedRules() {
        McpSchema.CallToolResult result = dispatcher(false, false)
                .call(tool("get_world_state"), args("dimension", "minecraft:overworld"));

        assertFalse(result.isError());
        assertEquals("{\"dimension\":\"minecraft:overworld\",\"gameTime\":12345,\"dayTime\":6000,\"raining\":true,"
                + "\"thundering\":false,\"difficulty\":\"normal\","
                + "\"gameRules\":{\"doDaylightCycle\":\"true\",\"keepInventory\":\"false\"}}", text(result));
        assertEquals(List.of("minecraft:overworld"), game.stateQueries);
    }

    @Test
    void getWorldStateNeedsAStringDimension() {
        assertEquals("dimension must be a string", text(dispatcher(false, false).call(tool("get_world_state"), args("dimension", 5))));
        assertEquals("dimension must be a string", text(dispatcher(false, false).call(tool("get_world_state"), Map.of())));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void listPermissionsReturnsOperatorsLevelsAndTheWhitelist() {
        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("list_permissions"), Map.of());

        assertFalse(result.isError());
        assertEquals("{\"operators\":[{\"name\":\"Alex\",\"uuid\":\"uuid-op\",\"level\":4},"
                + "{\"name\":\"Sam\",\"uuid\":\"uuid-op2\",\"level\":2}],\"whitelistEnabled\":true,"
                + "\"whitelist\":[{\"name\":\"Steve\",\"uuid\":\"uuid-1\"}]}", text(result));
        assertEquals(1, game.permissionCalls);
    }

    @Test
    void listPermissionsOnAnOpenServerWithNoOperators() {
        game.permissions = new Permissions(List.of(), false, List.of());
        assertEquals("{\"operators\":[],\"whitelistEnabled\":false,\"whitelist\":[]}",
                text(dispatcher(false, false).call(tool("list_permissions"), null)));
    }

    @Test
    void stateAndPermissionReadsThatTimeOutAreCancelledWithoutClaimingASideEffect() {
        game.pendingState = new CompletableFuture<>();
        game.pendingPermissions = new CompletableFuture<>();
        ToolDispatcher d = dispatcher(config(false, false, 32768, Duration.ofMillis(100)), game.services(), audit::add);

        assertEquals("timed out after 100 ms", text(d.call(tool("get_world_state"), args("dimension", "minecraft:overworld"))));
        assertEquals("timed out after 100 ms", text(d.call(tool("list_permissions"), Map.of())));
        assertTrue(game.pendingState.isCancelled());
        assertTrue(game.pendingPermissions.isCancelled());
    }

    // ---- inventory and log ----

    @Test
    void getInventoryReturnsItemIdsAndCountsBySlotAndNothingElse() {
        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("get_inventory"), args("player", "Steve"));

        assertFalse(result.isError());
        assertEquals("{\"player\":\"Steve\",\"stacks\":[{\"slot\":0,\"item\":\"minecraft:diamond_sword\",\"count\":1},"
                + "{\"slot\":36,\"item\":\"create:cogwheel\",\"count\":64}]}", text(result));
        assertEquals(List.of("Steve"), game.inventoryQueries);
    }

    @Test
    void getInventoryOfSomeoneWhoIsNotOnlineIsAnErrorWithTheGamesReason() {
        game.pendingInventory = CompletableFuture.failedFuture(new IllegalArgumentException("player 'Nobody' is not online"));

        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("get_inventory"), args("player", "Nobody"));

        assertTrue(result.isError());
        assertEquals("player 'Nobody' is not online", text(result));
    }

    @Test
    void getInventoryNeedsAPlayerName() {
        assertEquals("player must be a string", text(dispatcher(false, false).call(tool("get_inventory"), args("player", 5))));
        assertEquals("player must be a string", text(dispatcher(false, false).call(tool("get_inventory"), Map.of())));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void readLogMasksIpAddressesFlagsTheTextUntrustedAndDefaultsToFiftyLines() {
        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("read_log"), Map.of());

        assertFalse(result.isError());
        assertEquals("{\"untrusted\":true,\"lines\":[\"[12:00:00] [Server thread/INFO]: Done (1.2s)!\","
                + "\"[12:00:01] [Server thread/INFO]: Steve[/[ip]] logged in\",\"<Steve> hello\"]}", text(result));
        assertEquals(List.of(50), game.logQueries);
    }

    @Test
    void readLogPassesTheRequestedNumberOfLinesAndCutsLongOnes() {
        game.logLines = List.of("x".repeat(500));

        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("read_log"), args("lines", 7));

        assertEquals(List.of(7), game.logQueries);
        assertTrue(text(result).contains("x".repeat(400) + "..."), text(result));
        assertFalse(text(result).contains("x".repeat(401)));
    }

    @Test
    void readLogLineCountIsBoundedBeforeTheLogIsTouched() {
        ToolDispatcher d = dispatcher(false, false);
        assertEquals("lines must be between 1 and 200", text(d.call(tool("read_log"), args("lines", 0))));
        assertEquals("lines must be between 1 and 200", text(d.call(tool("read_log"), args("lines", 201))));
        assertEquals("lines must be an integer", text(d.call(tool("read_log"), args("lines", "ten"))));
        assertEquals(0, game.totalCalls());
        assertFalse(d.call(tool("read_log"), args("lines", 1)).isError());
        assertFalse(d.call(tool("read_log"), args("lines", 200)).isError());
    }

    @Test
    void readEventsReturnsTheLatestEventsAndFlagsPlayerTextUntrusted() {
        game.events.publish(EventType.JOIN, "Steve", "ignored");
        game.events.publish(EventType.CHAT, "Steve", "hello\nworld");

        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("read_events"), Map.of());

        assertFalse(result.isError());
        String json = text(result);
        assertTrue(json.contains("\"sequence\":1,"), json);
        assertTrue(json.contains("\"type\":\"join\",\"player\":\"Steve\",\"text\":\"\",\"untrusted\":false"), json);
        assertTrue(json.contains("\"type\":\"chat\",\"player\":\"Steve\",\"text\":\"hello world\",\"untrusted\":true"), json);
        assertTrue(json.endsWith("\"latest\":2,\"dropped\":0}"), json);
        assertEquals(List.of("-1,50"), game.eventReads);
    }

    @Test
    void readEventsResumesFromACursorAndHonoursTheLimit() {
        for (int i = 0; i < 5; i++) {
            game.events.publish(EventType.CHAT, "Steve", "m" + i);
        }

        String json = text(dispatcher(false, false).call(tool("read_events"), args("after", 2, "limit", 2)));

        assertTrue(json.contains("\"text\":\"m2\"") && json.contains("\"text\":\"m3\""), json);
        assertFalse(json.contains("\"text\":\"m4\"") || json.contains("\"text\":\"m1\""), json);
        assertEquals(List.of("2,2"), game.eventReads);
    }

    @Test
    void readEventsArgumentsAreCheckedBeforeTheFeedIsTouched() {
        ToolDispatcher d = dispatcher(false, false);
        assertEquals("after must be an integer", text(d.call(tool("read_events"), args("after", "x"))));
        assertEquals("limit must be an integer", text(d.call(tool("read_events"), args("limit", "x"))));
        assertEquals("limit must be between 1 and 200", text(d.call(tool("read_events"), args("limit", 0))));
        assertEquals("limit must be between 1 and 200", text(d.call(tool("read_events"), args("limit", 201))));
        assertFalse(d.call(tool("read_events"), args("limit", 1)).isError());
        assertFalse(d.call(tool("read_events"), args("limit", 200)).isError());
    }

    @Test
    void aLogThatCannotBeReadIsAnErrorNotACrash() {
        game.logFailure = new java.io.IOException("disk on fire");

        McpSchema.CallToolResult result = dispatcher(false, false).call(tool("read_log"), Map.of());

        assertTrue(result.isError());
        assertEquals("disk on fire", text(result));
    }

    @Test
    void anInventoryReadThatTimesOutIsCancelled() {
        game.pendingInventory = new CompletableFuture<>();
        ToolDispatcher d = dispatcher(config(false, false, 32768, Duration.ofMillis(100)), game.services(), audit::add);

        assertEquals("timed out after 100 ms", text(d.call(tool("get_inventory"), args("player", "Steve"))));
        assertTrue(game.pendingInventory.isCancelled());
    }

    // ---- typed write tools (ACT) ----

    private static Map<String, Object> fillArgs(Object... overrides) {
        Map<String, Object> map = args("dimension", "overworld", "min_x", 0, "min_y", -60, "min_z", 0,
                "max_x", 1, "max_y", -59, "max_z", 1, "block", "stone");
        for (int i = 0; i < overrides.length; i += 2) {
            map.put((String) overrides[i], overrides[i + 1]);
        }
        return map;
    }

    @Test
    void fillBlocksRunsTheExactCommandForTheBox() {
        assertDone(dispatcher(true, false).call(tool("fill_blocks"), fillArgs()));
        assertEquals(List.of("execute in minecraft:overworld run fill 0 -60 0 1 -59 1 minecraft:stone"), game.commands);
        assertAudit(0, "decision", "fill_blocks", "allow", "");
    }

    @Test
    void fillBlocksDryRunReportsTheVolumeAndCommandAndChangesNothing() {
        McpSchema.CallToolResult result = dispatcher(true, false).call(tool("fill_blocks"), fillArgs("dry_run", true));

        assertFalse(result.isError());
        assertEquals("{\"dryRun\":true,\"volume\":8,\"command\":"
                + "\"execute in minecraft:overworld run fill 0 -60 0 1 -59 1 minecraft:stone\"}", text(result));
        assertEquals(0, game.totalCalls(), "a dry run never reaches the game");
    }

    @Test
    void fillBlocksEnforcesTheVolumeCapAtTheBoundaryForRealAndDryRuns() {
        ToolDispatcher d = dispatcher(config(true, false, 8, Duration.ofSeconds(2)), game.services(), audit::add);

        assertFalse(d.call(tool("fill_blocks"), fillArgs()).isError(), "volume 8 == cap is allowed");
        McpSchema.CallToolResult over = d.call(tool("fill_blocks"), fillArgs("max_x", 2));
        assertEquals("volume 12 exceeds maxBlockVolume 8", text(over));
        assertEquals("volume 12 exceeds maxBlockVolume 8", text(d.call(tool("fill_blocks"), fillArgs("max_x", 2, "dry_run", true))));
        assertEquals(1, game.commands.size());
    }

    @Test
    void fillBlocksRejectsMalformedBoxesAndIdsWithoutTouchingTheGame() {
        ToolDispatcher d = dispatcher(true, false);
        assertEquals("max coordinates must be >= min coordinates", text(d.call(tool("fill_blocks"), fillArgs("max_x", -5))));
        assertEquals("max_y must be an integer", text(d.call(tool("fill_blocks"), fillArgs("max_y", 1.5))));
        assertTrue(text(d.call(tool("fill_blocks"), fillArgs("block", "stone run stop"))).startsWith("block must be a resource location"));
        assertTrue(text(d.call(tool("fill_blocks"), fillArgs("dimension", "x run stop"))).startsWith("dimension must be a resource location"));
        assertEquals("block must be a string", text(d.call(tool("fill_blocks"), fillArgs("block", 5))));
        assertEquals("dry_run must be true or false", text(d.call(tool("fill_blocks"), fillArgs("dry_run", "yes"))));
        assertEquals(0, game.totalCalls());
    }

    @Test
    void typedWriteToolsNeedWriteAccessAndNeverReachTheGameWithout() {
        ToolDispatcher d = dispatcher(false, true);
        for (String tool : new String[]{"fill_blocks", "teleport", "give_item", "send_message"}) {
            Map<String, Object> arguments = switch (tool) {
                case "fill_blocks" -> fillArgs();
                case "teleport" -> args("player", "Steve", "x", 0, "y", 0, "z", 0);
                case "give_item" -> args("player", "Steve", "item", "diamond");
                default -> args("message", "hi");
            };
            McpSchema.CallToolResult result = d.call(tool(tool), arguments);
            assertEquals("write access is disabled", text(result), tool);
        }
        assertEquals(0, game.totalCalls());
    }

    @Test
    void teleportBuildsTheCommandWithAndWithoutADimension() {
        ToolDispatcher d = dispatcher(true, false);

        assertDone(d.call(tool("teleport"), args("player", "Steve", "x", 1.5, "y", 64, "z", -2)));
        assertDone(d.call(tool("teleport"), args("player", "Steve", "x", 0, "y", 70, "z", 0, "dimension", "the_nether")));

        assertEquals(List.of("tp Steve 1.5 64 -2", "execute in minecraft:the_nether run tp Steve 0 70 0"), game.commands);
    }

    @Test
    void aTypedToolIsGatedByItsScopeNotByTheCommandItBuilds() {
        // "tp" is on the destructive list for free-form commands, but a typed teleport has fixed,
        // validated arguments, so write access is enough.
        assertEquals("admin commands are disabled",
                text(dispatcher(true, false).call(tool("run_command"), args("command", "tp Steve 0 0 0"))));
        assertFalse(dispatcher(true, false).call(tool("teleport"), args("player", "Steve", "x", 0, "y", 0, "z", 0)).isError());
    }

    @Test
    void giveItemDefaultsTheCountToOne() {
        ToolDispatcher d = dispatcher(true, false);

        assertDone(d.call(tool("give_item"), args("player", "Alex", "item", "diamond")));
        assertDone(d.call(tool("give_item"), args("player", "Alex", "item", "create:cogwheel", "count", 64)));

        assertEquals(List.of("give Alex minecraft:diamond 1", "give Alex create:cogwheel 64"), game.commands);
    }

    @Test
    void sendMessageSpeaksToEveryoneOrOnePlayer() {
        ToolDispatcher d = dispatcher(true, false);

        assertDone(d.call(tool("send_message"), args("message", "hello all")));
        assertDone(d.call(tool("send_message"), args("message", "psst", "player", "Steve")));

        assertEquals(List.of("say hello all", "tell Steve psst"), game.commands);
    }

    @Test
    void hostileArgumentsNeverReachTheGame() {
        ToolDispatcher d = dispatcher(true, true);
        assertTrue(text(d.call(tool("teleport"), args("player", "@a", "x", 0, "y", 0, "z", 0))).startsWith("player must be a player name"));
        assertTrue(text(d.call(tool("give_item"), args("player", "Steve;stop", "item", "diamond"))).startsWith("player must be a player name"));
        assertTrue(text(d.call(tool("give_item"), args("player", "Steve", "item", "diamond run stop"))).startsWith("item must be a resource location"));
        assertEquals("message must not contain control characters or line breaks",
                text(d.call(tool("send_message"), args("message", "hi\nop Eve"))));
        assertEquals("reason must not contain control characters or line breaks",
                text(d.call(tool("kick_player"), args("player", "Steve", "reason", "bye\nstop", "confirm", true))));
        assertEquals("count must be between 1 and 6400", text(d.call(tool("give_item"), args("player", "Steve", "item", "diamond", "count", 6401))));
        assertEquals("x must be between -30000000 and 30000000", text(d.call(tool("teleport"), args("player", "Steve", "x", 1e9, "y", 0, "z", 0))));
        assertEquals(0, game.totalCalls());
    }

    // ---- typed admin tools (ADMIN) ----

    @Test
    void typedAdminToolsAreRefusedWhileAdminIsDisabled() {
        ToolDispatcher d = dispatcher(true, false);
        for (String tool : new String[]{"kick_player", "ban_player", "save_world", "stop_server"}) {
            Map<String, Object> arguments = tool.endsWith("_player") ? args("player", "Steve", "confirm", true) : args("confirm", true);
            assertEquals("admin access is disabled", text(d.call(tool(tool), arguments)), tool);
        }
        assertEquals(0, game.totalCalls());
    }

    @Test
    void typedAdminToolsNeedConfirmationAndOnlyThenRun() {
        ToolDispatcher d = dispatcher(false, true);
        for (String tool : new String[]{"kick_player", "ban_player", "save_world", "stop_server"}) {
            Map<String, Object> arguments = tool.endsWith("_player") ? args("player", "Steve", "confirm", false) : args("confirm", false);
            assertEquals("admin action requires confirmation; call again with confirm=true", text(d.call(tool(tool), arguments)), tool);
        }
        assertEquals(0, game.totalCalls(), "nothing ran without confirmation");

        assertDone(d.call(tool("kick_player"), args("player", "Steve", "confirm", true)));
        assertDone(d.call(tool("kick_player"), args("player", "Alex", "reason", "AFK", "confirm", true)));
        assertDone(d.call(tool("ban_player"), args("player", "Eve", "reason", "griefing", "confirm", true)));
        assertDone(d.call(tool("save_world"), args("confirm", true)));
        assertDone(d.call(tool("stop_server"), args("confirm", true)));

        assertEquals(List.of("kick Steve", "kick Alex AFK", "ban Eve griefing", "save-all flush", "stop"), game.commands);
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
        assertEquals("registry listing is not supported by this adapter",
                text(d.call(tool("list_registry"), args("registry", "block"))));
        assertEquals("entity queries is not supported by this adapter", text(d.call(tool("list_entities"), entityArgs())));
        assertEquals("world state is not supported by this adapter",
                text(d.call(tool("get_world_state"), args("dimension", "minecraft:overworld"))));
        assertEquals("permission listing is not supported by this adapter", text(d.call(tool("list_permissions"), Map.of())));
        assertEquals("inventory reads is not supported by this adapter", text(d.call(tool("get_inventory"), args("player", "Steve"))));
        assertEquals("log reads is not supported by this adapter", text(d.call(tool("read_log"), Map.of())));
        assertEquals("event feed is not supported by this adapter", text(d.call(tool("read_events"), Map.of())));
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
