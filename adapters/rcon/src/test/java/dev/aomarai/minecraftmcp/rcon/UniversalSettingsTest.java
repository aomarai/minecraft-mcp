package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.ConfigException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UniversalSettingsTest {

    private static final Map<String, Object> NOTHING = Map.of();
    private static final Map<String, String> PASSWORD = Map.of("MINECRAFT_MCP_RCON_PASSWORD", "from the environment");

    private static String message(Map<String, Object> section, Map<String, String> env) {
        return assertThrows(ConfigException.class, () -> UniversalSettings.parse(section, env)).getMessage();
    }

    @Test
    void onlyThePasswordIsRequiredAndTheRestHasSafeDefaults() {
        UniversalConfig config = UniversalSettings.parse(NOTHING, PASSWORD);

        assertEquals("127.0.0.1", config.rcon().host());
        assertEquals(25575, config.rcon().port());
        assertEquals("from the environment", config.rcon().password());
        assertFalse(config.rcon().allowRemote());
        assertTrue(config.queryPort().isEmpty());
        assertTrue(config.serverDirectory().isEmpty());
    }

    @Test
    void everythingCanComeFromTheFileSection() {
        Map<String, Object> section = Map.of("rconHost", "localhost", "rconPort", 25580, "rconPassword", "from the file",
                "allowRemoteRcon", false, "queryPort", 25566, "serverDirectory", "/srv/minecraft");

        UniversalConfig config = UniversalSettings.parse(section, Map.of());

        assertEquals("localhost", config.rcon().host());
        assertEquals(25580, config.rcon().port());
        assertEquals("from the file", config.rcon().password());
        assertEquals(25566, config.queryPort().getAsInt());
        assertEquals(Path.of("/srv/minecraft"), config.serverDirectory().orElseThrow());
        assertFalse(config.rcon().allowRemote());
    }

    @Test
    void theEnvironmentWinsOverTheFile() {
        Map<String, Object> section = Map.of("rconHost", "localhost", "rconPort", 25580, "rconPassword", "from the file",
                "queryPort", 25566, "serverDirectory", "/from/file");
        Map<String, String> env = Map.of("MINECRAFT_MCP_RCON_HOST", "127.0.0.2", "MINECRAFT_MCP_RCON_PORT", " 25590 ",
                "MINECRAFT_MCP_RCON_PASSWORD", "from the env", "MINECRAFT_MCP_QUERY_PORT", "25567",
                "MINECRAFT_MCP_SERVER_DIRECTORY", "/from/env");

        UniversalConfig config = UniversalSettings.parse(section, env);

        assertEquals("127.0.0.2", config.rcon().host());
        assertEquals(25590, config.rcon().port());
        assertEquals("from the env", config.rcon().password());
        assertEquals(25567, config.queryPort().getAsInt());
        assertEquals(Path.of("/from/env"), config.serverDirectory().orElseThrow());
    }

    @Test
    void aRemoteHostNeedsToBeAllowedOnPurposeInTheFileOrTheEnvironment() {
        String refusal = message(Map.of("rconHost", "192.0.2.10"), PASSWORD);
        assertTrue(refusal.startsWith("invalid universal setting: host '192.0.2.10' is not this machine"), refusal);

        assertEquals("192.0.2.10", UniversalSettings.parse(Map.of("rconHost", "192.0.2.10", "allowRemoteRcon", true),
                PASSWORD).rcon().host());
        assertTrue(UniversalSettings.parse(Map.of("rconHost", "192.0.2.10"), Map.of("MINECRAFT_MCP_RCON_PASSWORD", "p",
                "MINECRAFT_MCP_ALLOW_REMOTE_RCON", "TRUE")).rcon().allowRemote());
        assertFalse(UniversalSettings.parse(NOTHING, Map.of("MINECRAFT_MCP_RCON_PASSWORD", "p",
                "MINECRAFT_MCP_ALLOW_REMOTE_RCON", " false ")).rcon().allowRemote());
    }

    @Test
    void aMissingPasswordSaysWhereToPutIt() {
        assertEquals("the RCON password is not set: put rconPassword in the \"universal\" section of the config file, "
                + "or set MINECRAFT_MCP_RCON_PASSWORD", message(NOTHING, Map.of()));
    }

    @Test
    void anEmptyPasswordIsRefusedByTheRconConfig() {
        assertEquals("invalid universal setting: password must not be empty (the game requires rcon.password)",
                message(Map.of("rconPassword", ""), Map.of()));
    }

    @Test
    void anUnknownSettingIsRejectedByName() {
        assertEquals("unknown universal setting: rconPasword", message(Map.of("rconPasword", "x"), PASSWORD));
    }

    @Test
    void theWrongTypeOfAFileValueIsRejectedByName() {
        assertEquals("rconPassword must be a string", message(Map.of("rconPassword", 5), Map.of()));
        assertEquals("rconPort must be an integer", message(Map.of("rconPort", "25575"), PASSWORD));
        assertEquals("rconPort must be an integer", message(Map.of("rconPort", 1.5), PASSWORD));
        assertEquals("allowRemoteRcon must be true or false", message(Map.of("allowRemoteRcon", "yes"), PASSWORD));
        assertEquals("queryPort must be an integer", message(Map.of("queryPort", "x"), PASSWORD));
        assertEquals("serverDirectory must be a string", message(Map.of("serverDirectory", 7), PASSWORD));
    }

    @Test
    void theWrongTypeOfAnEnvironmentValueIsRejectedByName() {
        assertEquals("MINECRAFT_MCP_RCON_PORT must be an integer, got: abc",
                message(NOTHING, Map.of("MINECRAFT_MCP_RCON_PASSWORD", "p", "MINECRAFT_MCP_RCON_PORT", "abc")));
        assertEquals("MINECRAFT_MCP_ALLOW_REMOTE_RCON must be true or false, got: maybe",
                message(NOTHING, Map.of("MINECRAFT_MCP_RCON_PASSWORD", "p", "MINECRAFT_MCP_ALLOW_REMOTE_RCON", "maybe")));
    }

    @Test
    void portsOutOfRangeAreRejected() {
        assertEquals("invalid universal setting: port must be between 1 and 65535", message(Map.of("rconPort", 0), PASSWORD));
        assertEquals("invalid universal setting: queryPort must be between 1 and 65535", message(Map.of("queryPort", 70000), PASSWORD));
    }

    @Test
    void noSettingLeaksThePasswordIntoAMessage() {
        String refusal = message(Map.of("rconHost", "192.0.2.10", "rconPassword", "very secret value"), Map.of());

        assertFalse(refusal.contains("very secret value"));
    }
}
