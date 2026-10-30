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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonlAuditLogTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final String TOKEN = "super-secret-token-value";

    @TempDir
    Path dir;

    private static AuditEntry entry(Map<String, Object> args, String detail) {
        return new AuditEntry(NOW, AuditEntry.PHASE_DECISION, "run_command", args, "allow", detail);
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
    void theFileIsPreparedOnceNotForEveryEntry() throws IOException {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path file = dir.resolve("logs").resolve("audit.jsonl");
        List<Path> prepared = new java.util.ArrayList<>();
        JsonlAuditLog log = new JsonlAuditLog(file, List.of(TOKEN), path -> {
            prepared.add(path);
            OwnerOnlyFile.prepare(path);
        });
        assertEquals(List.of(), prepared, "nothing is touched before the first entry");

        log.record(entry(Map.of(), "one"));
        log.record(entry(Map.of(), "two"));
        log.record(entry(Map.of(), "three"));

        assertEquals(List.of(file), prepared);
        assertEquals(3, lines(file).size());
    }

    @Test
    void aLogThatWasDeletedIsPreparedAgainSoItIsNeverCreatedWithTheDefaultMode() throws IOException {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path file = dir.resolve("audit.jsonl");
        int[] prepared = {0};
        JsonlAuditLog log = new JsonlAuditLog(file, List.of(), path -> {
            prepared[0]++;
            OwnerOnlyFile.prepare(path);
        });
        log.record(entry(Map.of(), "before"));

        Files.delete(file);
        log.record(entry(Map.of(), "after"));

        assertEquals(2, prepared[0]);
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(List.of("after"), lines(file).stream().map(l -> l.get("detail")).toList());
    }

    @Test
    void aFailedPreparationIsRetriedOnTheNextEntry() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        int[] attempts = {0};
        JsonlAuditLog log = new JsonlAuditLog(file, List.of(), path -> {
            if (attempts[0]++ == 0) {
                throw new IOException("disk not ready");
            }
            OwnerOnlyFile.prepare(path);
        });

        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> log.record(entry(Map.of(), "first")));
        assertTrue(failure.getMessage().startsWith("cannot write audit log"));
        log.record(entry(Map.of(), "second"));

        assertEquals(2, attempts[0]);
        assertEquals(1, lines(file).size());
    }

    @Test
    void writesOneValidJsonObjectPerLine() throws IOException {
        Path file = dir.resolve("logs").resolve("audit.jsonl");
        JsonlAuditLog log = new JsonlAuditLog(file, List.of(TOKEN));

        log.record(entry(Map.of("command", "say hi"), "ok"));
        log.record(new AuditEntry(NOW, AuditEntry.PHASE_RESULT, "server_info", Map.of(), "deny", "nope"));

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
    void theAuditFileIsOwnerOnlyAndStaysSoWhenLoosened() throws IOException {
        Path file = dir.resolve("logs").resolve("audit.jsonl");
        JsonlAuditLog log = new JsonlAuditLog(file, List.of());

        log.record(entry(Map.of(), "first"));
        assumeTrue(OwnerOnlyFileTest.posix());
        assertEquals("rw-------", OwnerOnlyFileTest.mode(file));

        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        log.record(entry(Map.of(), "second"));
        assertEquals("rw-------", OwnerOnlyFileTest.mode(file));
        assertEquals(2, lines(file).size());
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

    // ---- rotation ----

    private JsonlAuditLog rotating(Path file, long maxBytes) {
        return new JsonlAuditLog(file, List.of(TOKEN), OwnerOnlyFile::prepare, maxBytes);
    }

    /** How many bytes one entry with this detail takes in the file. */
    private long sizeOfOneEntry(String detail) throws IOException {
        Path probe = dir.resolve("probe.jsonl");
        new JsonlAuditLog(probe, List.of()).record(entry(Map.of(), detail));
        return Files.size(probe);
    }

    @Test
    void theFileIsRotatedExactlyWhenTheNextEntryWouldPassTheLimit() throws IOException {
        long one = sizeOfOneEntry("e1");
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = rotating(file, 2 * one);

        log.record(entry(Map.of(), "e1"));
        log.record(entry(Map.of(), "e2"));
        assertEquals(2 * one, Files.size(file), "a file exactly at the limit is not rotated");
        assertFalse(Files.exists(dir.resolve("audit.jsonl.1")));

        log.record(entry(Map.of(), "e3"));

        assertEquals(List.of("e3"), lines(file).stream().map(l -> l.get("detail")).toList());
        assertEquals(List.of("e1", "e2"), lines(dir.resolve("audit.jsonl.1")).stream().map(l -> l.get("detail")).toList());
    }

    @Test
    void oneByteUnderTheLimitStillTakesTheNextEntryAfterOneMoreByteDoesNot() throws IOException {
        long one = sizeOfOneEntry("e1");
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = rotating(file, 2 * one - 1);

        log.record(entry(Map.of(), "e1"));
        log.record(entry(Map.of(), "e2"));

        assertEquals(List.of("e2"), lines(file).stream().map(l -> l.get("detail")).toList());
        assertEquals(List.of("e1"), lines(dir.resolve("audit.jsonl.1")).stream().map(l -> l.get("detail")).toList());
    }

    @Test
    void anEntryLargerThanTheLimitIsStillWrittenWholeToAFileOfItsOwn() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = rotating(file, 10);

        log.record(entry(Map.of(), "first"));
        log.record(entry(Map.of(), "second"));

        assertEquals(List.of("second"), lines(file).stream().map(l -> l.get("detail")).toList());
        assertEquals(List.of("first"), lines(dir.resolve("audit.jsonl.1")).stream().map(l -> l.get("detail")).toList());
        assertFalse(Files.exists(dir.resolve("audit.jsonl.2")), "an empty file is never rotated");
    }

    @Test
    void onlyTheNewestRotatedFilesAreKept() throws IOException {
        long one = sizeOfOneEntry("e1");
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = rotating(file, one);

        for (int i = 1; i <= JsonlAuditLog.KEPT + 3; i++) {
            log.record(entry(Map.of(), "e" + i));
        }

        int newest = JsonlAuditLog.KEPT + 3;
        assertEquals(List.of("e" + newest), lines(file).stream().map(l -> l.get("detail")).toList());
        for (int n = 1; n <= JsonlAuditLog.KEPT; n++) {
            assertEquals(List.of("e" + (newest - n)),
                    lines(dir.resolve("audit.jsonl." + n)).stream().map(l -> l.get("detail")).toList());
        }
        assertFalse(Files.exists(dir.resolve("audit.jsonl." + (JsonlAuditLog.KEPT + 1))));
    }

    @Test
    void rotatedAndNewFilesAreOwnerOnly() throws IOException {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        long one = sizeOfOneEntry("e1");
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = rotating(file, one);

        log.record(entry(Map.of(), "e1"));
        log.record(entry(Map.of(), "e2"));

        assertTrue(OwnerOnlyFile.holds(file));
        assertTrue(OwnerOnlyFile.holds(dir.resolve("audit.jsonl.1")));
    }

    @Test
    void aRotationThatFailsFailsTheEntryAndLosesNothing() throws IOException {
        long one = sizeOfOneEntry("e1");
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = rotating(file, one);
        log.record(entry(Map.of(), "e1"));
        // A directory where the first rotated file belongs and something inside it, so the move cannot happen.
        Files.createDirectories(dir.resolve("audit.jsonl.1").resolve("blocker"));
        Files.createDirectories(dir.resolve("audit.jsonl.2"));
        Files.writeString(dir.resolve("audit.jsonl.2").resolve("blocker"), "x");
        Files.createDirectories(dir.resolve("audit.jsonl.4"));
        Files.writeString(dir.resolve("audit.jsonl.4").resolve("blocker"), "x");

        assertThrows(UncheckedIOException.class, () -> log.record(entry(Map.of(), "e2")));

        assertEquals(List.of("e1"), lines(file).stream().map(l -> l.get("detail")).toList());
    }
}
