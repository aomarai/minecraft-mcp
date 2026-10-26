package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** What a tool call reports about its own duration: in the audit entry, and as a warning when it stutters. */
class ToolTimingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private final AtomicLong nanos = new AtomicLong();
    private final List<AuditEntry> audit = new ArrayList<>();

    private static long millis(long value) {
        return Duration.ofMillis(value).toNanos();
    }

    /** A game whose list_players holds the main thread for as long as the test says, on the calling thread. */
    private ToolDispatcher dispatcher(long mainThreadMillis, long otherMillis, AuditLog log) {
        PlayerReader players = () -> {
            nanos.addAndGet(millis(otherMillis));
            return MainThread.call(Runnable::run, () -> {
                nanos.addAndGet(millis(mainThreadMillis));
                return List.of();
            });
        };
        McpConfig config = new McpConfig("127.0.0.1", 25599, false, false, 32768, Duration.ofSeconds(2));
        return new ToolDispatcher(config, new GameServices(Side.SERVER, players), log, CLOCK, nanos::get);
    }

    private static ToolDescriptor listPlayers() {
        return ToolCatalog.defaults().tools().stream().filter(t -> t.name().equals("list_players")).findFirst().orElseThrow();
    }

    @Test
    void theResultEntryCarriesBothDurationsAndTheDecisionEntryNone() {
        ToolDispatcher d = dispatcher(12, 30, audit::add);

        assertFalse(d.call(listPlayers(), Map.of()).isError());

        assertEquals(2, audit.size());
        assertEquals(CallTiming.Result.NONE, audit.get(0).timing());
        assertEquals(new CallTiming.Result(millis(12), millis(42)), audit.get(1).timing());
    }

    @Test
    void aCallThatHoldsTheMainThreadOverOneTickIsAnOverrunAndIsWarnedAbout() {
        try (LogCapture log = new LogCapture()) {
            dispatcher(51, 0, audit::add).call(listPlayers(), Map.of());

            assertTrue(audit.get(1).timing().overrun());
            assertTrue(log.lines().contains("{0} held the game thread for {1} ms; a tick is {2} ms [list_players, 51, 50]"),
                    log.lines().toString());
        }
    }

    @Test
    void aCallWithinOneTickIsNotWarnedAboutHoweverLongItTakesOverall() {
        try (LogCapture log = new LogCapture()) {
            dispatcher(50, 5_000, audit::add).call(listPlayers(), Map.of());

            assertFalse(audit.get(1).timing().overrun());
            assertTrue(log.lines().stream().noneMatch(line -> line.contains("held the game thread")), log.lines().toString());
        }
    }

    @Test
    void theTimingIsDroppedWhateverHappensToTheCall() {
        ToolDispatcher denied = dispatcher(1, 0, audit::add);
        denied.call(ToolCatalog.defaults().tools().stream().filter(t -> t.name().equals("run_command")).findFirst().orElseThrow(),
                Map.of("command", "say hi"));
        assertNull(CallTiming.current(), "a denied call");

        ToolDispatcher unaudited = dispatcher(1, 0, entry -> {
            throw new IllegalStateException("disk full");
        });
        unaudited.call(listPlayers(), Map.of());
        assertNull(CallTiming.current(), "a call that cannot be audited");

        dispatcher(1, 0, audit::add).call(listPlayers(), Map.of());
        assertNull(CallTiming.current(), "a call that ran");
    }

    @Test
    void theAuditFileHasTheDurationsInMicrosecondsAndTheOverrunFlag() throws Exception {
        Path file = dir.resolve("audit.jsonl");
        ToolDispatcher d = dispatcher(51, 1, new JsonlAuditLog(file, List.of()));

        d.call(listPlayers(), Map.of());

        List<String> lines = Files.readAllLines(file);
        Map<String, Object> decision = McpJsonDefaults.getMapper().readValue(lines.get(0), new TypeRef<Map<String, Object>>() {
        });
        Map<String, Object> result = McpJsonDefaults.getMapper().readValue(lines.get(1), new TypeRef<Map<String, Object>>() {
        });
        assertFalse(decision.containsKey("mainThreadMicros"));
        assertFalse(decision.containsKey("totalMicros"));
        assertFalse(decision.containsKey("overrun"));
        assertEquals(51_000, ((Number) result.get("mainThreadMicros")).longValue());
        assertEquals(52_000, ((Number) result.get("totalMicros")).longValue());
        assertEquals(Boolean.TRUE, result.get("overrun"));
    }
}
