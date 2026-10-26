package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dev.aomarai.minecraftmcp.core.ToolResults.text;
import static org.junit.jupiter.api.Assertions.*;

/** The tools that move and act as the local player, through the dispatcher, against the fake game. */
class PlayerToolsTest {

    private static final int LIMIT = 30_000_000;

    private final FakeGame game = new FakeGame();

    private ToolDispatcher dispatcher(boolean writes, Duration timeout, GameServices services) {
        return new ToolDispatcher(new McpConfig("127.0.0.1", 25599, writes, false, 32768, timeout), services, entry -> {
        });
    }

    private ToolDispatcher dispatcher() {
        return dispatcher(true, Duration.ofSeconds(2), game.services(Side.CLIENT, Capability.PLAYER_CONTROL));
    }

    private static ToolDescriptor tool(String name) {
        return ToolCatalog.defaults().tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, Object> xyz(Object x, Object y, Object z) {
        Map<String, Object> map = new HashMap<>();
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        return map;
    }

    @Test
    void eachToolPassesItsExactArgumentsToThePlayerAndReturnsWhatTheGameSays() {
        ToolDispatcher d = dispatcher();

        assertEquals("moved", text(d.call(tool("move_to"), xyz(1.5, 64, -2.25))));
        assertEquals("looked", text(d.call(tool("look_at"), xyz(0, 70.5, 3))));
        assertEquals("jumped", text(d.call(tool("jump"), Map.of())));
        assertEquals("broke", text(d.call(tool("break_block"), xyz(10, 64, -20))));
        assertEquals("placed", text(d.call(tool("place_block"), xyz(11, 65, -21))));
        assertEquals("interacted", text(d.call(tool("interact"), xyz(12, 66, -22))));

        assertEquals(List.of("move_to 1.5 64.0 -2.25", "look_at 0.0 70.5 3.0", "jump", "break_block 10 64 -20",
                "place_block 11 65 -21", "interact 12 66 -22"), game.playerActions);
    }

    @Test
    void positionsAreBoundedAtBothEnds() {
        ToolDispatcher d = dispatcher();
        String message = "x must be between -30000000 and 30000000";

        assertFalse(d.call(tool("move_to"), xyz(LIMIT, -LIMIT, LIMIT)).isError());
        assertFalse(d.call(tool("look_at"), xyz(-LIMIT, 0, 0)).isError());
        assertEquals(message, text(d.call(tool("move_to"), xyz(LIMIT + 1, 0, 0))));
        assertEquals(message, text(d.call(tool("move_to"), xyz(-LIMIT - 1, 0, 0))));
        assertEquals(message, text(d.call(tool("look_at"), xyz(LIMIT + 1, 0, 0))));
        assertEquals("y must be between -30000000 and 30000000", text(d.call(tool("move_to"), xyz(0, LIMIT + 1, 0))));
        assertEquals("z must be between -30000000 and 30000000", text(d.call(tool("move_to"), xyz(0, 0, -LIMIT - 1))));
        assertEquals("x must be a number", text(d.call(tool("move_to"), xyz("1", 0, 0))));

        for (String name : List.of("break_block", "place_block", "interact")) {
            assertFalse(d.call(tool(name), xyz(LIMIT, -LIMIT, LIMIT)).isError(), name);
            assertEquals(message, text(d.call(tool(name), xyz(LIMIT + 1, 0, 0))), name);
            assertEquals(message, text(d.call(tool(name), xyz(-LIMIT - 1, 0, 0))), name);
            assertEquals("y must be between -30000000 and 30000000", text(d.call(tool(name), xyz(0, LIMIT + 1, 0))), name);
            assertEquals("z must be between -30000000 and 30000000", text(d.call(tool(name), xyz(0, 0, LIMIT + 1))), name);
            assertEquals("x must be an integer", text(d.call(tool(name), xyz(1.5, 0, 0))), name);
        }
        assertEquals(2 + 3, game.playerActions.size(), "only the valid calls reached the player");
    }

    @Test
    void withoutWriteAccessNoPlayerToolRunsAndNothingReachesTheGame() {
        ToolDispatcher readOnly = dispatcher(false, Duration.ofSeconds(2), game.services(Side.CLIENT, Capability.PLAYER_CONTROL));

        for (String name : List.of("move_to", "look_at", "jump", "break_block", "place_block", "interact")) {
            McpSchema.CallToolResult result = readOnly.call(tool(name), xyz(1, 2, 3));
            assertTrue(result.isError(), name);
            assertEquals("write access is disabled", text(result), name);
        }
        assertEquals(List.of(), game.playerActions);
    }

    @Test
    void noToolNamesAPlayerSoNoneCanActOnSomeoneElse() {
        for (String name : List.of("move_to", "look_at", "jump", "break_block", "place_block", "interact")) {
            Map<String, Object> properties = tool(name).inputSchema().properties();
            assertTrue(java.util.Set.of("x", "y", "z").containsAll(properties.keySet()), name + ": " + properties.keySet());
            assertEquals(Boolean.FALSE, tool(name).inputSchema().additionalProperties(), name);
            assertEquals(Side.CLIENT, tool(name).side(), name);
        }
    }

    @Test
    void aMissingPortIsUnsupportedAStuckActionTimesOutAndARefusalKeepsItsReason() {
        ToolDispatcher none = dispatcher(true, Duration.ofSeconds(2), game.services(Side.CLIENT));
        assertEquals("player control is not supported by this adapter", text(none.call(tool("jump"), Map.of())));

        game.pendingPlayerAction = new CompletableFuture<>();
        ToolDispatcher quick = dispatcher(true, Duration.ofMillis(100), game.services(Side.CLIENT, Capability.PLAYER_CONTROL));
        assertEquals("timed out after 100 ms; the call may still have taken effect", text(quick.call(tool("jump"), Map.of())));
        assertTrue(game.pendingPlayerAction.isCancelled());

        game.pendingPlayerAction = CompletableFuture.failedFuture(new IllegalStateException("that block is out of reach"));
        McpSchema.CallToolResult refused = dispatcher().call(tool("break_block"), xyz(1, 2, 3));
        assertTrue(refused.isError());
        assertEquals("that block is out of reach", text(refused));
    }
}
