package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads {@link McpConfig} and the bearer token from a JSON file, with
 * environment-variable overrides.
 *
 * <p>Security behavior:
 * <ul>
 *   <li>A missing file is created with secure defaults (loopback, read-only,
 *       admin disabled) and a freshly generated random token.</li>
 *   <li>The file is restricted to its owner (see {@link OwnerOnlyFile}) before
 *       any secret is written, and re-restricted on every load.</li>
 *   <li>The token is never logged and never part of {@link Loaded#toString()}.</li>
 *   <li>Environment variables win over file values but are never written back
 *       to the file.</li>
 *   <li>Unknown keys and invalid values fail with a message naming the key,
 *       instead of silently falling back.</li>
 * </ul>
 *
 * <p>Environment variables: {@code MINECRAFT_MCP_HOST}, {@code _PORT},
 * {@code _ALLOW_WRITES}, {@code _ALLOW_ADMIN}, {@code _ALLOW_REMOTE}, {@code _MAX_BLOCK_VOLUME},
 * {@code _CALL_TIMEOUT_MS}, {@code _TOKEN}.</p>
 */
public final class ConfigLoader {

    static final int MIN_TOKEN_LENGTH = 16;
    private static final int GENERATED_TOKEN_BYTES = 32;
    private static final String ENV_PREFIX = "MINECRAFT_MCP_";
    /** The variable that, when set, supplies the token instead of the file. */
    static final String TOKEN_ENV = ENV_PREFIX + "TOKEN";
    private static final System.Logger LOG = System.getLogger(ConfigLoader.class.getName());
    private static final TypeRef<Map<String, Object>> OBJECT = new TypeRef<>() {
    };

    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String ALLOW_WRITES = "allowWrites";
    private static final String ALLOW_ADMIN = "allowAdmin";
    private static final String ALLOW_REMOTE = "allowRemote";
    private static final String ALLOWED_HOSTS = "allowedHosts";
    private static final String TLS_KEYSTORE = "tlsKeystore";
    private static final String TLS_KEYSTORE_PASSWORD = "tlsKeystorePassword";
    private static final String MAX_BLOCK_VOLUME = "maxBlockVolume";
    private static final String CALL_TIMEOUT_MS = "callTimeoutMillis";
    private static final String ALLOWED_COMMANDS = "allowedCommands";
    private static final String DESTRUCTIVE_COMMANDS = "destructiveCommands";
    private static final String TOKEN = "token";
    /** A section the universal tier reads for itself (see {@link #universalSection}); the core only checks its shape. */
    private static final String UNIVERSAL = "universal";
    private static final List<String> KEYS = List.of(
            HOST, PORT, ALLOW_WRITES, ALLOW_ADMIN, ALLOW_REMOTE, ALLOWED_HOSTS, TLS_KEYSTORE,
            TLS_KEYSTORE_PASSWORD, MAX_BLOCK_VOLUME, CALL_TIMEOUT_MS, ALLOWED_COMMANDS,
            DESTRUCTIVE_COMMANDS, TOKEN, UNIVERSAL);

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
            OwnerOnlyFile.restrict(file);
        }

        Map<String, Object> effective = defaults();
        effective.putAll(persisted);
        applyEnvironment(effective, env);

        String token = text(effective, TOKEN);
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new ConfigException(TOKEN + " must be at least " + MIN_TOKEN_LENGTH + " characters");
        }
        McpConfig config = build(effective, file.toAbsolutePath().getParent());
        LOG.log(System.Logger.Level.INFO, "Loaded configuration from {0}", file);
        return new Loaded(config, token, created);
    }

    /**
     * The {@code universal} object of the config file: the settings of the universal (RCON) tier, which
     * lives in the same file as everything else. Empty when the file or the section does not exist. The core
     * does not interpret it.
     */
    public static Map<String, Object> universalSection(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        Object section = readFile(McpJsonDefaults.getMapper(), file).get(UNIVERSAL);
        if (section == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<?, ?>) section).forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /**
     * Replaces the token in the config file with a fresh random one, keeping every other setting.
     * The new token is deliberately not returned: it is read from the owner-only file, never
     * printed or logged. A running server keeps the old token until it is restarted.
     */
    static void rotateToken(Path file) throws IOException {
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        Map<String, Object> values = Files.exists(file) ? readFile(mapper, file) : defaults();
        values.put(TOKEN, generateToken());
        write(mapper, file, values);
    }

    /**
     * For a second endpoint in the same process (the client's, next to the integrated server's): the variables
     * named {@code MINECRAFT_MCP_<scope>_X} become {@code MINECRAFT_MCP_X}, and the plain {@code MINECRAFT_MCP_X}
     * ones, which belong to the first endpoint, are left out.
     */
    public static Map<String, String> scopedEnvironment(Map<String, String> env, String scope) {
        String prefix = ENV_PREFIX + scope + "_";
        Map<String, String> scoped = new LinkedHashMap<>();
        env.forEach((name, value) -> {
            if (name.startsWith(prefix)) {
                scoped.put(ENV_PREFIX + name.substring(prefix.length()), value);
            }
        });
        return scoped;
    }

    /**
     * Gives a config file that does not exist yet a different default port. Everything else keeps its default and
     * the token is generated on the first load as usual. An existing file is left alone.
     */
    public static void seedPort(Path file, int port) throws IOException {
        if (!Files.exists(file)) {
            write(McpJsonDefaults.getMapper(), file, new LinkedHashMap<>(Map.of(PORT, port)));
        }
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
        map.put(ALLOW_REMOTE, d.allowRemote());
        map.put(ALLOWED_HOSTS, List.of());
        map.put(TLS_KEYSTORE, "");
        map.put(TLS_KEYSTORE_PASSWORD, "");
        map.put(MAX_BLOCK_VOLUME, d.maxBlockVolume());
        map.put(CALL_TIMEOUT_MS, d.callTimeout().toMillis());
        map.put(ALLOWED_COMMANDS, List.of());
        map.put(DESTRUCTIVE_COMMANDS, List.of());
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
        if (parsed.get(UNIVERSAL) != null && !(parsed.get(UNIVERSAL) instanceof Map)) {
            throw new ConfigException(UNIVERSAL + " must be an object");
        }
        return new LinkedHashMap<>(parsed);
    }

    private static void applyEnvironment(Map<String, Object> values, Map<String, String> env) {
        override(values, env, HOST, "HOST", Kind.IDENTITY);
        override(values, env, PORT, "PORT", Kind.NUMBER);
        override(values, env, ALLOW_WRITES, "ALLOW_WRITES", Kind.BOOLEAN);
        override(values, env, ALLOW_ADMIN, "ALLOW_ADMIN", Kind.BOOLEAN);
        override(values, env, ALLOW_REMOTE, "ALLOW_REMOTE", Kind.BOOLEAN);
        override(values, env, ALLOWED_HOSTS, "ALLOWED_HOSTS", Kind.LIST);
        override(values, env, TLS_KEYSTORE, "TLS_KEYSTORE", Kind.IDENTITY);
        override(values, env, TLS_KEYSTORE_PASSWORD, "TLS_KEYSTORE_PASSWORD", Kind.IDENTITY);
        override(values, env, MAX_BLOCK_VOLUME, "MAX_BLOCK_VOLUME", Kind.NUMBER);
        override(values, env, CALL_TIMEOUT_MS, "CALL_TIMEOUT_MS", Kind.NUMBER);
        override(values, env, ALLOWED_COMMANDS, "ALLOWED_COMMANDS", Kind.LIST);
        override(values, env, DESTRUCTIVE_COMMANDS, "DESTRUCTIVE_COMMANDS", Kind.LIST);
        override(values, env, TOKEN, "TOKEN", Kind.IDENTITY);
    }

    private enum Kind { IDENTITY, NUMBER, BOOLEAN, LIST }

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
            case LIST -> Arrays.stream(raw.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
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

    private static McpConfig build(Map<String, Object> values, Path configDir) {
        try {
            return new McpConfig(
                    text(values, HOST),
                    integer(values, PORT),
                    bool(values, ALLOW_WRITES),
                    bool(values, ALLOW_ADMIN),
                    integer(values, MAX_BLOCK_VOLUME),
                    Duration.ofMillis(longValue(values, CALL_TIMEOUT_MS)),
                    strings(values, ALLOWED_COMMANDS),
                    strings(values, DESTRUCTIVE_COMMANDS),
                    bool(values, ALLOW_REMOTE),
                    strings(values, ALLOWED_HOSTS),
                    tls(values, configDir));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("invalid configuration: " + e.getMessage(), e);
        }
    }

    /**
     * The keystore settings. A password without a keystore is an error rather than a quiet fall back to plain HTTP:
     * someone who typed a password believes the endpoint is encrypted.
     */
    private static Optional<Tls> tls(Map<String, Object> values, Path configDir) {
        String store = Objects.requireNonNullElse(text(values, TLS_KEYSTORE), "").strip();
        String password = Objects.requireNonNullElse(text(values, TLS_KEYSTORE_PASSWORD), "");
        if (store.isEmpty()) {
            if (!password.isEmpty()) {
                throw new ConfigException(TLS_KEYSTORE_PASSWORD + " is set but " + TLS_KEYSTORE
                        + " is not, so the endpoint would still speak plain HTTP");
            }
            return Optional.empty();
        }
        Path keystore = configDir.resolve(store).normalize();
        if (!Files.isRegularFile(keystore)) {
            throw new ConfigException(TLS_KEYSTORE + " does not exist: " + keystore);
        }
        if (password.isEmpty()) {
            throw new ConfigException(TLS_KEYSTORE_PASSWORD + " must be set when " + TLS_KEYSTORE + " is");
        }
        return Optional.of(new Tls(keystore, password));
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

    private static Set<String> strings(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof List<?> items) || !items.stream().allMatch(String.class::isInstance)) {
            throw new ConfigException(key + " must be a list of strings");
        }
        return items.stream().map(String.class::cast).collect(Collectors.toUnmodifiableSet());
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
        long number = longValue(values, key);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ConfigException(key + " is out of range: " + number);
        }
        return (int) number;
    }

    private static void write(McpJsonMapper mapper, Path file, Map<String, Object> values) throws IOException {
        // Restrict before writing so the secret never sits in a world-readable file.
        OwnerOnlyFile.prepare(file);

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
}
