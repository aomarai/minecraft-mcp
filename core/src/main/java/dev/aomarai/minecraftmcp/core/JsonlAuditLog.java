package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AuditLog} that appends one JSON object per line (JSONL).
 *
 * <p>Secrets passed to the constructor are replaced by {@code [redacted]} in
 * every string of every entry, and long strings are truncated, so a pasted
 * token or a huge command cannot leak into or flood the log. Each entry is
 * written with a single append under a lock, so lines never interleave. The file is
 * owner-only (see {@link OwnerOnlyFile}): every entry checks the mode with one stat and prepares the file
 * (directories, mode) only when it is missing or loosened, so a call does not pay for that setup every time but
 * a log that someone loosened or deleted is still made owner-only again before anything is written.</p>
 *
 * <p>The log is bounded: before an entry that would take the file past {@link #MAX_BYTES} the file is rotated to
 * {@code <name>.1}, the older ones move up to {@code <name>.}{@value #KEPT}, and the oldest is deleted. Only whole
 * lines are written, so none is split or lost. A new file is made owner-only like the first. A rotation that fails
 * fails the entry, so the call is refused like for any other unwritable log.</p>
 *
 * <p>The write stays on the calling thread on purpose. The dispatcher refuses a call whose
 * audit entry cannot be written, which needs the outcome of the write before the tool runs.</p>
 */
public final class JsonlAuditLog implements AuditLog {

    static final String REDACTED = "[redacted]";
    static final int MAX_STRING = 256;
    /** The size a log file may reach: about 40,000 entries. */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** Rotated files kept next to the current one. */
    static final int KEPT = 4;

    /** Creates the file and its directories and makes it owner-only. */
    @FunctionalInterface
    interface Prepare {
        void prepare(Path file) throws IOException;
    }

    private final Path file;
    private final List<String> secrets;
    private final Prepare prepare;
    private final long maxBytes;

    public JsonlAuditLog(Path file, List<String> secrets) {
        this(file, secrets, OwnerOnlyFile::prepare, MAX_BYTES);
    }

    JsonlAuditLog(Path file, List<String> secrets, Prepare prepare) {
        this(file, secrets, prepare, MAX_BYTES);
    }

    JsonlAuditLog(Path file, List<String> secrets, Prepare prepare, long maxBytes) {
        this.file = file;
        this.secrets = secrets.stream().filter(secret -> !secret.isEmpty()).toList();
        this.prepare = prepare;
        this.maxBytes = maxBytes;
    }

    @Override
    public synchronized void append(AuditEntry entry) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", entry.time().toString());
        line.put("phase", entry.phase());
        line.put("tool", entry.tool());
        line.put("arguments", clean(entry.arguments()));
        line.put("decision", entry.decision());
        line.put("detail", clean(entry.detail()));
        if (!entry.timing().equals(CallTiming.Result.NONE)) {
            line.put("mainThreadMicros", entry.timing().mainThreadNanos() / 1_000);
            line.put("totalMicros", entry.timing().totalNanos() / 1_000);
            line.put("overrun", entry.timing().overrun());
        }
        try {
            byte[] bytes = (McpJsonDefaults.getMapper().writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
            if (!OwnerOnlyFile.holds(file)) {
                prepare.prepare(file);
            }
            if (Files.size(file) > 0 && Files.size(file) + bytes.length > maxBytes) {
                rotate();
                prepare.prepare(file);
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write audit log " + file, e);
        }
    }

    /** {@code file} becomes {@code file.1}, {@code file.1} becomes {@code file.2} and so on; the one past the last is deleted. */
    private void rotate() throws IOException {
        Files.deleteIfExists(rotated(KEPT));
        for (int i = KEPT - 1; i >= 1; i--) {
            if (Files.exists(rotated(i))) {
                Files.move(rotated(i), rotated(i + 1));
            }
        }
        Files.move(file, rotated(1));
    }

    private Path rotated(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }

    private Object clean(Object value) {
        if (value instanceof String text) {
            String redacted = text;
            for (String secret : secrets) {
                redacted = redacted.replace(secret, REDACTED);
            }
            return redacted.length() > MAX_STRING ? redacted.substring(0, MAX_STRING) + "..." : redacted;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), clean(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::clean).toList();
        }
        return value;
    }
}
