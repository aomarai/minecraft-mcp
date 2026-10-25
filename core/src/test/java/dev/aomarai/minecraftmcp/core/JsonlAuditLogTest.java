package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonlAuditLogTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final String TOKEN = "super-secret-token-value";

    @TempDir
    Path dir;

    private static AuditEntry entry(Map<String, Object> args, String detail) {
        return new AuditEntry(NOW, AuditEntry.DECISION, "run_command", args, "allow", detail);
    }

    private List<Map<String, Object>> lines(Path file) throws IOException {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            out.add(McpJsonDefaults.getMapper().readValue(line, new TypeRef<Map<String, Object>>() {
            }));
        }
        return out;
    }

    @Test
    void writesOneValidJsonObjectPerLine() throws IOException {
        Path file = dir.resolve("logs").resolve("audit.jsonl");
        JsonlAuditLog log = new JsonlAuditLog(file, List.of(TOKEN));

        log.record(entry(Map.of("command", "say hi"), "ok"));
        log.record(new AuditEntry(NOW, AuditEntry.RESULT, "server_info", Map.of(), "deny", "nope"));

        List<Map<String, Object>> lines = lines(file);
        assertEquals(2, lines.size());
        assertEquals("2026-01-02T03:04:05Z", lines.get(0).get("ts"));
        assertEquals("decision", lines.get(0).get("phase"));
        assertEquals("run_command", lines.get(0).get("tool"));
        assertEquals(Map.of("command", "say hi"), lines.get(0).get("arguments"));
        assertEquals("allow", lines.get(0).get("decision"));
        assertEquals("ok", lines.get(0).get("detail"));
        assertEquals("result", lines.get(1).get("phase"));
        assertEquals("deny", lines.get(1).get("decision"));
    }

    @Test
    void appendsToAnExistingFile() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        new JsonlAuditLog(file, List.of()).record(entry(Map.of(), "first"));
        new JsonlAuditLog(file, List.of()).record(entry(Map.of(), "second"));
        assertEquals(2, lines(file).size());
    }

    @Test
    void newlinesInValuesDoNotBreakTheLineFormat() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        new JsonlAuditLog(file, List.of()).record(entry(Map.of("command", "a\nb\r\nc"), "x\ny"));
        assertEquals(1, Files.readAllLines(file).size());
        assertEquals("a\nb\r\nc", ((Map<?, ?>) lines(file).get(0).get("arguments")).get("command"));
    }

    @Test
    void redactsSecretsEverywhereIncludingNestedValues() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = new JsonlAuditLog(file, List.of(TOKEN, ""));

        log.record(entry(Map.of(
                "command", "say " + TOKEN,
                "nested", Map.of("inner", TOKEN + TOKEN),
                "list", List.of(TOKEN, "fine"),
                "number", 7), "detail " + TOKEN));

        String raw = Files.readString(file);
        assertFalse(raw.contains(TOKEN), raw);
        Map<String, Object> line = lines(file).get(0);
        assertEquals("detail [redacted]", line.get("detail"));
        Map<?, ?> args = (Map<?, ?>) line.get("arguments");
        assertEquals("say [redacted]", args.get("command"));
        assertEquals(Map.of("inner", "[redacted][redacted]"), args.get("nested"));
        assertEquals(List.of("[redacted]", "fine"), args.get("list"));
        assertEquals(7, args.get("number"));
    }

    @Test
    void emptySecretsAreIgnored() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        new JsonlAuditLog(file, List.of("")).record(entry(Map.of("command", "say hi"), "ok"));
        assertEquals("say hi", ((Map<?, ?>) lines(file).get(0).get("arguments")).get("command"));
    }

    @Test
    void longStringsAreTruncated() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        String exactly = "x".repeat(JsonlAuditLog.MAX_STRING);
        String longer = "y".repeat(JsonlAuditLog.MAX_STRING + 1);
        new JsonlAuditLog(file, List.of()).record(entry(Map.of("exact", exactly, "longer", longer), "ok"));

        Map<?, ?> args = (Map<?, ?>) lines(file).get(0).get("arguments");
        assertEquals(exactly, args.get("exact"));
        assertEquals("y".repeat(JsonlAuditLog.MAX_STRING) + "...", args.get("longer"));
    }

    @Test
    void anUnwritableFileThrowsSoTheDispatcherCanRefuse() throws IOException {
        Path directory = dir.resolve("a-directory");
        Files.createDirectories(directory);
        JsonlAuditLog log = new JsonlAuditLog(directory, List.of());

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> log.record(entry(Map.of(), "ok")));
        assertTrue(e.getMessage().contains("cannot write audit log"));
    }

    @Test
    void discardingLogAcceptsEntries() {
        assertDoesNotThrow(() -> AuditLog.discarding().record(entry(Map.of(), "ok")));
    }

    @Test
    void noneServicesHaveNoPorts() {
        GameServices none = GameServices.none();
        assertNull(none.info());
        assertNull(none.players());
        assertNull(none.world());
        assertNull(none.commands());
    }
}
