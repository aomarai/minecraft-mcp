package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConfigLoaderTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("config").resolve("minecraft-mcp.json");
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private void writeConfig(String json) throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), json);
    }

    private static ConfigException failure(Path file, Map<String, String> env) {
        return assertThrows(ConfigException.class, () -> ConfigLoader.load(file, env));
    }

    @Test
    void missingFileIsCreatedWithSecureDefaultsAndAToken() throws IOException {
        ConfigLoader.Loaded loaded = ConfigLoader.load(file(), Map.of());

        assertTrue(loaded.created());
        assertEquals(McpConfig.defaults(), loaded.config());
        assertTrue(loaded.token().length() >= ConfigLoader.MIN_TOKEN_LENGTH);
        assertTrue(Files.exists(file()));
        String json = Files.readString(file());
        assertTrue(json.contains("\"host\": \"127.0.0.1\""));
        assertTrue(json.contains("\"allowWrites\": false"));
        assertTrue(json.contains("\"allowAdmin\": false"));
        assertTrue(json.contains("\"token\": \"" + loaded.token() + "\""));
    }

    @Test
    void reloadingReturnsTheSameTokenAndDoesNotRecreate() throws IOException {
        ConfigLoader.Loaded first = ConfigLoader.load(file(), Map.of());
        ConfigLoader.Loaded second = ConfigLoader.load(file(), Map.of());

        assertFalse(second.created());
        assertEquals(first.token(), second.token());
        assertEquals(first.config(), second.config());
    }

    @Test
    void completeFileIsNotRewritten() throws IOException {
        String raw = "{ \"token\":\"" + TOKEN + "\",   \"port\": 4000 }";
        writeConfig(raw);
        ConfigLoader.load(file(), Map.of());
        assertEquals(raw, Files.readString(file()));
    }

    @Test
    void generatedTokensDiffer() {
        assertNotEquals(ConfigLoader.generateToken(), ConfigLoader.generateToken());
    }

    @Test
    void newFileIsOwnerOnlyOnPosix() throws IOException {
        assumeTrue(posix());
        ConfigLoader.load(file(), Map.of());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())));
    }

    @Test
    void existingWorldReadableFileIsTightenedOnPosix() throws IOException {
        assumeTrue(posix());
        writeConfig("{\"token\": \"" + TOKEN + "\"}");
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r--r--"));

        ConfigLoader.load(file(), Map.of());

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())));
    }

    @Test
    void tokenNeverAppearsInLogsOrToString() throws IOException {
        List<String> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Object[] params = record.getParameters();
                logged.add(record.getMessage() + " " + (params == null ? "" : List.of(params)));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            ConfigLoader.Loaded loaded = ConfigLoader.load(file(), Map.of());
            ConfigLoader.load(file(), Map.of("MINECRAFT_MCP_TOKEN", TOKEN));

            assertFalse(logged.isEmpty(), "loading should log something");
            for (String line : logged) {
                assertFalse(line.contains(loaded.token()), line);
                assertFalse(line.contains(TOKEN), line);
            }
            assertFalse(loaded.toString().contains(loaded.token()));
            assertTrue(loaded.toString().contains("<redacted>"));
        } finally {
            root.removeHandler(handler);
        }
    }

    @Test
    void fileValuesAreUsedAndMissingKeysFallBackToDefaults() throws IOException {
        writeConfig("{\"host\": \"localhost\", \"port\": 4000, \"allowWrites\": true, \"token\": \"" + TOKEN + "\"}");

        ConfigLoader.Loaded loaded = ConfigLoader.load(file(), Map.of());

        assertEquals(new McpConfig("localhost", 4000, true, false, 32768, Duration.ofSeconds(8)), loaded.config());
        assertEquals(TOKEN, loaded.token());
        assertFalse(loaded.created());
    }

    @Test
    void fileWithoutATokenGetsOneGeneratedAndPersistedWithoutLosingKeys() throws IOException {
        writeConfig("{\"port\": 4000}");

        ConfigLoader.Loaded loaded = ConfigLoader.load(file(), Map.of());

        assertEquals(4000, loaded.config().port());
        String json = Files.readString(file());
        assertTrue(json.contains("\"port\": 4000"));
        assertTrue(json.contains(loaded.token()));
        assertEquals(loaded.token(), ConfigLoader.load(file(), Map.of()).token());
    }

    @Test
    void environmentOverridesWinButAreNotWrittenBack() throws IOException {
        writeConfig("{\"port\": 4000, \"allowWrites\": false, \"token\": \"" + TOKEN + "\"}");
        Map<String, String> env = Map.of(
                "MINECRAFT_MCP_HOST", "0.0.0.0",
                "MINECRAFT_MCP_PORT", "5000",
                "MINECRAFT_MCP_ALLOW_WRITES", "TRUE",
                "MINECRAFT_MCP_ALLOW_ADMIN", " true ",
                "MINECRAFT_MCP_MAX_BLOCK_VOLUME", "100",
                "MINECRAFT_MCP_CALL_TIMEOUT_MS", "1500",
                "MINECRAFT_MCP_TOKEN", "env-token-env-token-1234");

        ConfigLoader.Loaded loaded = ConfigLoader.load(file(), env);

        assertEquals(new McpConfig("0.0.0.0", 5000, true, true, 100, Duration.ofMillis(1500)), loaded.config());
        assertEquals("env-token-env-token-1234", loaded.token());
        String json = Files.readString(file());
        assertTrue(json.contains("\"port\": 4000"));
        assertFalse(json.contains("env-token"));
        assertFalse(json.contains("0.0.0.0"));
    }

    @Test
    void environmentCanProvideFalseAndMixedCase() throws IOException {
        writeConfig("{\"allowWrites\": true, \"allowAdmin\": true, \"token\": \"" + TOKEN + "\"}");
        ConfigLoader.Loaded loaded = ConfigLoader.load(file(),
                Map.of("MINECRAFT_MCP_ALLOW_WRITES", "False", "MINECRAFT_MCP_ALLOW_ADMIN", "false"));
        assertFalse(loaded.config().allowWrites());
        assertFalse(loaded.config().allowAdmin());
    }

    @Test
    void unrelatedEnvironmentIsIgnored() throws IOException {
        ConfigLoader.Loaded loaded = ConfigLoader.load(file(), Map.of("PORT", "1", "MINECRAFT_MCP_NOPE", "x"));
        assertEquals(McpConfig.defaults(), loaded.config());
    }

    @Test
    void invalidEnvironmentValuesNameTheVariable() {
        ConfigException port = failure(file(), Map.of("MINECRAFT_MCP_PORT", "abc"));
        assertTrue(port.getMessage().contains("MINECRAFT_MCP_PORT"), port.getMessage());
        ConfigException flag = failure(file(), Map.of("MINECRAFT_MCP_ALLOW_ADMIN", "yes"));
        assertTrue(flag.getMessage().contains("MINECRAFT_MCP_ALLOW_ADMIN"), flag.getMessage());
    }

    @Test
    void invalidPortAndTimeoutFailWithAClearMessage() {
        ConfigException port = failure(file(), Map.of("MINECRAFT_MCP_PORT", "70000"));
        assertTrue(port.getMessage().contains("port must be between 1 and 65535"), port.getMessage());
        ConfigException timeout = failure(file(), Map.of("MINECRAFT_MCP_CALL_TIMEOUT_MS", "0"));
        assertTrue(timeout.getMessage().contains("callTimeout must be positive"), timeout.getMessage());
        ConfigException volume = failure(file(), Map.of("MINECRAFT_MCP_MAX_BLOCK_VOLUME", "0"));
        assertTrue(volume.getMessage().contains("maxBlockVolume"), volume.getMessage());
    }

    @Test
    void invalidFileValuesFailWithAClearMessage() throws IOException {
        writeConfig("{\"port\": 0, \"token\": \"" + TOKEN + "\"}");
        assertTrue(failure(file(), Map.of()).getMessage().contains("port must be between 1 and 65535"));
    }

    @Test
    void wrongTypesInTheFileNameTheKey() throws IOException {
        String[][] cases = {
                {"{\"host\": 5}", "host must be a string"},
                {"{\"port\": \"80\"}", "port must be an integer"},
                {"{\"port\": 1.5}", "port must be an integer"},
                {"{\"allowWrites\": \"yes\"}", "allowWrites must be true or false"},
                {"{\"allowAdmin\": 1}", "allowAdmin must be true or false"},
                {"{\"callTimeoutMillis\": \"8\"}", "callTimeoutMillis must be an integer"},
                {"{\"token\": 12345}", "token must be a string"},
        };
        for (String[] c : cases) {
            writeConfig(c[0]);
            assertEquals(c[1], failure(file(), Map.of()).getMessage(), c[0]);
        }
    }

    @Test
    void portOutsideIntRangeIsRejected() throws IOException {
        writeConfig("{\"port\": 99999999999, \"token\": \"" + TOKEN + "\"}");
        assertEquals("port is out of range: 99999999999", failure(file(), Map.of()).getMessage());
        writeConfig("{\"port\": -99999999999, \"token\": \"" + TOKEN + "\"}");
        assertEquals("port is out of range: -99999999999", failure(file(), Map.of()).getMessage());
    }

    @Test
    void portAtIntBoundariesIsRangeCheckedByConfig() throws IOException {
        writeConfig("{\"port\": 2147483647, \"token\": \"" + TOKEN + "\"}");
        assertTrue(failure(file(), Map.of()).getMessage().contains("port must be between"));
        writeConfig("{\"port\": -2147483648, \"token\": \"" + TOKEN + "\"}");
        assertTrue(failure(file(), Map.of()).getMessage().contains("port must be between"));
    }

    @Test
    void unknownKeysAreRejected() throws IOException {
        writeConfig("{\"allowWrite\": true}");
        assertEquals("unknown config key: allowWrite", failure(file(), Map.of()).getMessage());
    }

    @Test
    void malformedJsonIsRejected() throws IOException {
        writeConfig("{not json");
        ConfigException e = failure(file(), Map.of());
        assertTrue(e.getMessage().startsWith("config file is not a valid JSON object"), e.getMessage());
        assertNotNull(e.getCause());
        writeConfig("null");
        assertTrue(failure(file(), Map.of()).getMessage().startsWith("config file is not a valid JSON object"));
        writeConfig("[1]");
        assertTrue(failure(file(), Map.of()).getMessage().startsWith("config file is not a valid JSON object"));
    }

    @Test
    void shortTokensAreRejected() throws IOException {
        writeConfig("{\"token\": \"short\"}");
        assertEquals("token must be at least 16 characters", failure(file(), Map.of()).getMessage());
        assertEquals("token must be at least 16 characters",
                failure(file(), Map.of("MINECRAFT_MCP_TOKEN", "x".repeat(15))).getMessage());
        writeConfig("{\"token\": \"" + "x".repeat(16) + "\"}");
        assertDoesNotThrow(() -> ConfigLoader.load(file(), Map.of()));
    }

    @Test
    void relativePathWithoutParentWorks() throws IOException {
        Path relative = dir.resolve("plain.json");
        ConfigLoader.Loaded loaded = ConfigLoader.load(relative, Map.of());
        assertTrue(loaded.created());
        assertTrue(Files.exists(relative));
    }

    @Test
    void loadUsesTheProcessEnvironmentByDefault() throws IOException {
        ConfigLoader.Loaded loaded = ConfigLoader.load(file());
        assertNotNull(loaded.token());
    }

    @Test
    void configExceptionKeepsItsCause() {
        Throwable cause = new RuntimeException("x");
        assertSame(cause, new ConfigException("m", cause).getCause());
        assertEquals("m", new ConfigException("m").getMessage());
    }
}
