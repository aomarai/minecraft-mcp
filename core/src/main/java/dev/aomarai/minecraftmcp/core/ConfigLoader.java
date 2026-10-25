package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@link McpConfig} and the bearer token from a JSON file, with
 * environment-variable overrides.
 *
 * <p>Security behavior:
 * <ul>
 *   <li>A missing file is created with secure defaults (loopback, read-only,
 *       admin disabled) and a freshly generated random token.</li>
 *   <li>The file is restricted to its owner (mode {@code 0600} on POSIX)
 *       before any secret is written, and re-restricted on every load.</li>
 *   <li>The token is never logged and never part of {@link Loaded#toString()}.</li>
 *   <li>Environment variables win over file values but are never written back
 *       to the file.</li>
 *   <li>Unknown keys and invalid values fail with a message naming the key,
 *       instead of silently falling back.</li>
 * </ul>
 *
 * <p>Environment variables: {@code MINECRAFT_MCP_HOST}, {@code _PORT},
 * {@code _ALLOW_WRITES}, {@code _ALLOW_ADMIN}, {@code _MAX_BLOCK_VOLUME},
 * {@code _CALL_TIMEOUT_MS}, {@code _TOKEN}.</p>
 */
public final class ConfigLoader {

    static final int MIN_TOKEN_LENGTH = 16;
    private static final int GENERATED_TOKEN_BYTES = 32;
    private static final String ENV_PREFIX = "MINECRAFT_MCP_";
    private static final System.Logger LOG = System.getLogger(ConfigLoader.class.getName());
    private static final TypeRef<Map<String, Object>> OBJECT = new TypeRef<>() {
    };

    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String ALLOW_WRITES = "allowWrites";
    private static final String ALLOW_ADMIN = "allowAdmin";
    private static final String MAX_BLOCK_VOLUME = "maxBlockVolume";
    private static final String CALL_TIMEOUT_MS = "callTimeoutMillis";
    private static final String TOKEN = "token";
    private static final List<String> KEYS = List.of(
            HOST, PORT, ALLOW_WRITES, ALLOW_ADMIN, MAX_BLOCK_VOLUME, CALL_TIMEOUT_MS, TOKEN);

    private ConfigLoader() {
    }

    /** Result of a load. The token is a secret: it is excluded from {@link #toString()}. */
    public record Loaded(McpConfig config, String token, boolean created) {
        @Override
        public String toString() {
            return "Loaded[config=" + config + ", token=<redacted>, created=" + created + "]";
        }
    }

    public static Loaded load(Path file) throws IOException {
        return load(file, System.getenv());
    }

    public static Loaded load(Path file, Map<String, String> env) throws IOException {
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        boolean created = !Files.exists(file);
        Map<String, Object> persisted = created ? defaults() : readFile(mapper, file);

        String fileToken = text(persisted, TOKEN);
        boolean generated = fileToken == null;
        if (generated) {
            persisted.put(TOKEN, generateToken());
        }
        if (generated) {
            write(mapper, file, persisted);
        } else {
            restrictToOwner(file);
        }

        Map<String, Object> effective = defaults();
        effective.putAll(persisted);
        applyEnvironment(effective, env);

        String token = text(effective, TOKEN);
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new ConfigException(TOKEN + " must be at least " + MIN_TOKEN_LENGTH + " characters");
        }
        McpConfig config = build(effective);
        LOG.log(System.Logger.Level.INFO, "Loaded configuration from {0}", file);
        return new Loaded(config, token, created);
    }

    static String generateToken() {
        byte[] bytes = new byte[GENERATED_TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, Object> defaults() {
        McpConfig d = McpConfig.defaults();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(HOST, d.host());
        map.put(PORT, d.port());
        map.put(ALLOW_WRITES, d.allowWrites());
        map.put(ALLOW_ADMIN, d.allowAdmin());
        map.put(MAX_BLOCK_VOLUME, d.maxBlockVolume());
        map.put(CALL_TIMEOUT_MS, d.callTimeout().toMillis());
        return map;
    }

    private static Map<String, Object> readFile(McpJsonMapper mapper, Path file) throws IOException {
        Map<String, Object> parsed;
        try {
            parsed = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), OBJECT);
        } catch (IOException | RuntimeException e) {
            throw new ConfigException("config file is not a valid JSON object: " + file, e);
        }
        if (parsed == null) {
            throw new ConfigException("config file is not a valid JSON object: " + file);
        }
        for (String key : parsed.keySet()) {
            if (!KEYS.contains(key)) {
                throw new ConfigException("unknown config key: " + key);
            }
        }
        return new LinkedHashMap<>(parsed);
    }

    private static void applyEnvironment(Map<String, Object> values, Map<String, String> env) {
        override(values, env, HOST, "HOST", Kind.IDENTITY);
        override(values, env, PORT, "PORT", Kind.NUMBER);
        override(values, env, ALLOW_WRITES, "ALLOW_WRITES", Kind.BOOLEAN);
        override(values, env, ALLOW_ADMIN, "ALLOW_ADMIN", Kind.BOOLEAN);
        override(values, env, MAX_BLOCK_VOLUME, "MAX_BLOCK_VOLUME", Kind.NUMBER);
        override(values, env, CALL_TIMEOUT_MS, "CALL_TIMEOUT_MS", Kind.NUMBER);
        override(values, env, TOKEN, "TOKEN", Kind.IDENTITY);
    }

    private enum Kind { IDENTITY, NUMBER, BOOLEAN }

    private static void override(Map<String, Object> values, Map<String, String> env, String key,
                                 String suffix, Kind kind) {
        String name = ENV_PREFIX + suffix;
        String raw = env.get(name);
        if (raw == null) {
            return;
        }
        values.put(key, switch (kind) {
            case IDENTITY -> raw;
            case NUMBER -> parseLong(name, raw);
            case BOOLEAN -> parseBoolean(name, raw);
        });
    }

    private static Long parseLong(String name, String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ConfigException(name + " must be an integer, got: " + raw, e);
        }
    }

    private static Boolean parseBoolean(String name, String raw) {
        String value = raw.strip();
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        throw new ConfigException(name + " must be true or false, got: " + raw);
    }

    private static McpConfig build(Map<String, Object> values) {
        try {
            return new McpConfig(
                    text(values, HOST),
                    integer(values, PORT),
                    bool(values, ALLOW_WRITES),
                    bool(values, ALLOW_ADMIN),
                    integer(values, MAX_BLOCK_VOLUME),
                    Duration.ofMillis(longValue(values, CALL_TIMEOUT_MS)));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("invalid configuration: " + e.getMessage(), e);
        }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new ConfigException(key + " must be a string");
        }
        return s;
    }

    private static boolean bool(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Boolean b)) {
            throw new ConfigException(key + " must be true or false");
        }
        return b;
    }

    private static long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new ConfigException(key + " must be an integer");
    }

    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new ConfigException(key + " is out of range: " + number);
            }
            return (int) number;
        }
        throw new ConfigException(key + " must be an integer");
    }

    private static void write(McpJsonMapper mapper, Path file, Map<String, Object> values) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        // Create without truncating, so the file exists before it is restricted.
        Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        // Restrict before writing so the secret never sits in a world-readable file.
        restrictToOwner(file);

        StringBuilder out = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            out.append("  ").append(mapper.writeValueAsString(entry.getKey())).append(": ")
                    .append(mapper.writeValueAsString(entry.getValue()))
                    .append(++i < values.size() ? ",\n" : "\n");
        }
        out.append("}\n");
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }

    /** Owner read/write only. Mode 0600 on POSIX; best effort where the file system has no such modes. */
    private static void restrictToOwner(Path file) {
        File f = file.toFile();
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setExecutable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
    }
}
