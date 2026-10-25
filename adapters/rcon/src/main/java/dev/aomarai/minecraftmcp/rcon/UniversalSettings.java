package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.ConfigException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns the {@code universal} section of the config file, and the environment, into a
 * {@link UniversalConfig}. The environment wins over the file, so a container can be configured without
 * writing the password to disk.
 *
 * <table>
 *   <caption>Settings</caption>
 *   <tr><th>File key</th><th>Environment variable</th><th>Default</th></tr>
 *   <tr><td>rconHost</td><td>MINECRAFT_MCP_RCON_HOST</td><td>127.0.0.1</td></tr>
 *   <tr><td>rconPort</td><td>MINECRAFT_MCP_RCON_PORT</td><td>25575</td></tr>
 *   <tr><td>rconPassword</td><td>MINECRAFT_MCP_RCON_PASSWORD</td><td>none: required</td></tr>
 *   <tr><td>allowRemoteRcon</td><td>MINECRAFT_MCP_ALLOW_REMOTE_RCON</td><td>false</td></tr>
 *   <tr><td>queryPort</td><td>MINECRAFT_MCP_QUERY_PORT</td><td>none: Query is not used</td></tr>
 *   <tr><td>serverDirectory</td><td>MINECRAFT_MCP_SERVER_DIRECTORY</td><td>none: no log, events or player list</td></tr>
 * </table>
 */
final class UniversalSettings {

    static final String PASSWORD_ENV = "MINECRAFT_MCP_RCON_PASSWORD";

    private static final String ENV_PREFIX = "MINECRAFT_MCP_";
    private static final List<String> KEYS = List.of("rconHost", "rconPort", "rconPassword", "allowRemoteRcon",
            "queryPort", "serverDirectory");

    private UniversalSettings() {
    }

    static UniversalConfig parse(Map<String, Object> section, Map<String, String> env) {
        for (String key : section.keySet()) {
            if (!KEYS.contains(key)) {
                throw new ConfigException("unknown universal setting: " + key);
            }
        }
        String password = text(section, env, "rconPassword", "RCON_PASSWORD");
        if (password == null) {
            throw new ConfigException("the RCON password is not set: put rconPassword in the \"universal\" section of the "
                    + "config file, or set " + PASSWORD_ENV);
        }
        try {
            RconConfig rcon = new RconConfig(
                    orDefault(text(section, env, "rconHost", "RCON_HOST"), "127.0.0.1"),
                    integer(section, env, "rconPort", "RCON_PORT").orElse(25575),
                    password,
                    bool(section, env, "allowRemoteRcon", "ALLOW_REMOTE_RCON"));
            Optional<Path> directory = Optional.ofNullable(text(section, env, "serverDirectory", "SERVER_DIRECTORY"))
                    .map(Path::of);
            return new UniversalConfig(rcon, integer(section, env, "queryPort", "QUERY_PORT"), directory);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("invalid universal setting: " + e.getMessage(), e);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String text(Map<String, Object> section, Map<String, String> env, String key, String suffix) {
        String fromEnv = env.get(ENV_PREFIX + suffix);
        if (fromEnv != null) {
            return fromEnv;
        }
        Object value = section.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ConfigException(key + " must be a string");
        }
        return text;
    }

    private static OptionalInt integer(Map<String, Object> section, Map<String, String> env, String key, String suffix) {
        String fromEnv = env.get(ENV_PREFIX + suffix);
        if (fromEnv != null) {
            try {
                return OptionalInt.of(Integer.parseInt(fromEnv.strip()));
            } catch (NumberFormatException e) {
                throw new ConfigException(ENV_PREFIX + suffix + " must be an integer, got: " + fromEnv, e);
            }
        }
        Object value = section.get(key);
        if (value == null) {
            return OptionalInt.empty();
        }
        if (value instanceof Integer number) {
            return OptionalInt.of(number);
        }
        throw new ConfigException(key + " must be an integer");
    }

    private static boolean bool(Map<String, Object> section, Map<String, String> env, String key, String suffix) {
        String fromEnv = env.get(ENV_PREFIX + suffix);
        if (fromEnv != null) {
            if (fromEnv.strip().equalsIgnoreCase("true")) {
                return true;
            }
            if (fromEnv.strip().equalsIgnoreCase("false")) {
                return false;
            }
            throw new ConfigException(ENV_PREFIX + suffix + " must be true or false, got: " + fromEnv);
        }
        Object value = section.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new ConfigException(key + " must be true or false");
    }
}
