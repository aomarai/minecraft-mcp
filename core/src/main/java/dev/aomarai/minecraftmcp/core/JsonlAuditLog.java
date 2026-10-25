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
 * owner-only (see {@link OwnerOnlyFile}).</p>
 */
public final class JsonlAuditLog implements AuditLog {

    static final String REDACTED = "[redacted]";
    static final int MAX_STRING = 256;

    private final Path file;
    private final List<String> secrets;

    public JsonlAuditLog(Path file, List<String> secrets) {
        this.file = file;
        this.secrets = secrets.stream().filter(secret -> !secret.isEmpty()).toList();
    }

    @Override
    public synchronized void record(AuditEntry entry) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", entry.time().toString());
        line.put("phase", entry.phase());
        line.put("tool", entry.tool());
        line.put("arguments", clean(entry.arguments()));
        line.put("decision", entry.decision());
        line.put("detail", clean(entry.detail()));
        try {
            OwnerOnlyFile.prepare(file);
            Files.writeString(file, McpJsonDefaults.getMapper().writeValueAsString(line) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write audit log " + file, e);
        }
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
