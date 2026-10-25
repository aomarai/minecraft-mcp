package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.Capability;
import dev.aomarai.minecraftmcp.core.EventFeed;
import dev.aomarai.minecraftmcp.core.GameServices;
import dev.aomarai.minecraftmcp.core.RuntimeInfo;
import dev.aomarai.minecraftmcp.core.Side;
import dev.aomarai.minecraftmcp.core.ToolCatalog;
import dev.aomarai.minecraftmcp.core.ToolDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UniversalServicesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path dir;

    private static RconConfig rcon() {
        return new RconConfig("127.0.0.1", 25575, "a password", false);
    }

    private UniversalServices services(OptionalInt queryPort, Optional<Path> serverDirectory) {
        return new UniversalServices(new UniversalConfig(rcon(), queryPort, serverDirectory), TIMEOUT, Clock.systemUTC());
    }

    private static List<String> tools(GameServices services) {
        return ToolCatalog.defaults().availableFor(services).tools().stream().map(ToolDescriptor::name).toList();
    }

    @Test
    void withOnlyRconTheServerCanRunCommandsAndSayWhatItIs() {
        try (UniversalServices universal = services(OptionalInt.empty(), Optional.empty())) {
            assertEquals(Set.of(Capability.RUNTIME_INFO, Capability.COMMANDS), universal.services().capabilities());
            assertEquals(Side.SERVER, universal.services().side());
        }
    }

    @Test
    void withTheServerFolderTheLogAndTheEventsAreOfferedToo() {
        try (UniversalServices universal = services(OptionalInt.empty(), Optional.of(dir))) {
            assertEquals(Set.of(Capability.RUNTIME_INFO, Capability.COMMANDS, Capability.LOGS, Capability.EVENTS),
                    universal.services().capabilities());
        }
    }

    @Test
    void thePlayerListNeedsBothQueryAndTheServerFolder() {
        try (UniversalServices queryOnly = services(OptionalInt.of(25565), Optional.empty())) {
            assertEquals(Set.of(Capability.RUNTIME_INFO, Capability.COMMANDS), queryOnly.services().capabilities());
        }
        try (UniversalServices both = services(OptionalInt.of(25565), Optional.of(dir))) {
            assertEquals(Set.of(Capability.RUNTIME_INFO, Capability.COMMANDS, Capability.LOGS, Capability.EVENTS,
                    Capability.PLAYERS), both.services().capabilities());
        }
    }

    @Test
    void theToolsAdvertisedAreExactlyThoseTheUniversalTierCanServe() {
        try (UniversalServices universal = services(OptionalInt.of(25565), Optional.of(dir))) {
            assertEquals(List.of("server_info", "list_players", "read_log", "fill_blocks", "teleport",
                    "give_item", "send_message", "kick_player", "ban_player", "save_world", "stop_server", "read_events",
                    "run_command", "run_admin_command"), tools(universal.services()));
        }
    }

    @Test
    void theToolsWithoutTheFolderAreOnlyCommandsAndServerInfo() {
        try (UniversalServices universal = services(OptionalInt.empty(), Optional.empty())) {
            assertEquals(List.of("server_info", "fill_blocks", "teleport", "give_item", "send_message", "kick_player",
                    "ban_player", "save_world", "stop_server", "run_command", "run_admin_command"), tools(universal.services()));
        }
    }

    @Test
    void everyUniversalToolIsAlsoAToolTheModsOffer() {
        List<String> mods = ToolCatalog.defaults().onSide(Side.SERVER).tools().stream().map(ToolDescriptor::name).toList();
        try (UniversalServices universal = services(OptionalInt.of(25565), Optional.of(dir))) {
            assertTrue(mods.containsAll(tools(universal.services())));
        }
    }

    @Test
    void whatTheUniversalTierCannotReadIsAbsentNotStubbed() {
        try (UniversalServices universal = services(OptionalInt.of(25565), Optional.of(dir))) {
            List<String> offered = tools(universal.services());

            for (String missing : List.of("query_blocks", "list_registry", "list_entities", "get_world_state",
                    "list_permissions", "get_inventory", "take_screenshot")) {
                assertFalse(offered.contains(missing), missing);
            }
        }
    }

    @Test
    void serverInfoSaysUniversalRconAndAnUnknownVersionWhenQueryIsNotConfigured() {
        try (UniversalServices universal = services(OptionalInt.empty(), Optional.empty())) {
            String described = universal.services().find(RuntimeInfo.class).orElseThrow().describe();

            assertEquals("universal/RCON / Minecraft unknown / server", described);
        }
    }

    @Test
    void serverInfoReportsTheVersionQueryGives() throws Exception {
        try (FakeQueryServer query = new FakeQueryServer();
             UniversalServices universal = new UniversalServices(
                     new UniversalConfig(rcon(), OptionalInt.of(query.port()), Optional.empty()), TIMEOUT, Clock.systemUTC())) {
            String described = universal.services().find(RuntimeInfo.class).orElseThrow().describe();

            assertEquals("universal/RCON / Minecraft 1.21.1 / server", described);
        }
    }

    @Test
    void theEventFeedFollowsTheServersLog() throws Exception {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        Path log = logs.resolve("latest.log");
        Files.writeString(log, "[10:00:00] [Server thread/INFO]: Done (1.0s)!\n");
        try (UniversalServices universal = services(OptionalInt.empty(), Optional.of(dir))) {
            EventFeed feed = universal.services().find(EventFeed.class).orElseThrow();
            Thread.sleep(200);
            Files.writeString(log, "[10:00:01] [Server thread/INFO]: <Steve> hello\n", java.nio.file.StandardOpenOption.APPEND);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (feed.read(-1, 10).events().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }

            assertEquals("hello", feed.read(-1, 10).events().get(0).text());
        }
    }

    @Test
    void theLogToolReadsTheServersLog() throws Exception {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "one\ntwo\n");
        try (UniversalServices universal = services(OptionalInt.empty(), Optional.of(dir))) {
            var reader = universal.services().find(dev.aomarai.minecraftmcp.core.LogReader.class).orElseThrow();

            assertEquals(List.of("one", "two"), reader.tail(10));
        }
    }

    @Test
    void thePlayerListIsBuiltFromQueryTheGamesDataCommandsAndTheOperatorList() throws Exception {
        Files.writeString(dir.resolve("ops.json"), "[{\"uuid\":\"x\",\"name\":\"steve\",\"level\":4}]");
        try (FakeQueryServer query = new FakeQueryServer(); FakeRconServer game = new FakeRconServer()) {
            game.replies = command -> List.of(switch (command.substring(command.lastIndexOf(' ') + 1)) {
                case "Pos" -> command.split(" ")[3] + " has the following entity data: [1.5d, 64.0d, -2.5d]";
                case "Dimension" -> command.split(" ")[3] + " has the following entity data: \"minecraft:overworld\"";
                default -> command.split(" ")[3] + " has the following entity data: [I; 0, 0, 0, 7]";
            });
            RconConfig rcon = new RconConfig("127.0.0.1", game.port(), FakeRconServer.PASSWORD, false);
            try (UniversalServices universal = new UniversalServices(
                    new UniversalConfig(rcon, OptionalInt.of(query.port()), Optional.of(dir)), TIMEOUT, Clock.systemUTC())) {
                var players = universal.services().find(dev.aomarai.minecraftmcp.core.PlayerReader.class).orElseThrow()
                        .listPlayers().get(20, TimeUnit.SECONDS);

                assertEquals(List.of("Steve", "Alex"), players.stream().map(p -> p.name()).toList());
                assertEquals(List.of(true, false), players.stream().map(p -> p.op()).toList());
                assertEquals("minecraft:overworld", players.get(0).dimension());
                assertEquals("00000000-0000-0000-0000-000000000007", players.get(0).uuid());
            }
            assertTrue(noLiveThreadNamed("minecraft-mcp-players"), "closing ends the player worker");
        }
    }

    private static boolean noLiveThreadNamed(String name) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals(name) && t.isAlive())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void closingStopsTheRconClientAndTheLogTailer() throws Exception {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        Path log = logs.resolve("latest.log");
        Files.writeString(log, "[10:00:00] [Server thread/INFO]: Done (1.0s)!\n");
        UniversalServices universal = services(OptionalInt.empty(), Optional.of(dir));
        EventFeed feed = universal.services().find(EventFeed.class).orElseThrow();
        var commands = universal.services().find(dev.aomarai.minecraftmcp.core.CommandRunner.class).orElseThrow();
        Thread.sleep(200);

        universal.close();

        assertEquals(List.of("the RCON client is closed"),
                commands.runCommand("list", TIMEOUT).get(10, TimeUnit.SECONDS).feedback());
        Files.writeString(log, "[10:00:01] [Server thread/INFO]: <Steve> too late\n", java.nio.file.StandardOpenOption.APPEND);
        Thread.sleep(1200);
        assertEquals(List.of(), feed.read(-1, 10).events(), "a closed tailer follows nothing");
    }

    @Test
    void thePublicStartUsesTheSameConfigAndCloses() throws IOException {
        UniversalServices universal = UniversalServices.start(
                new UniversalConfig(rcon(), OptionalInt.empty(), Optional.empty()), TIMEOUT);

        assertEquals(EnumSet.of(Capability.RUNTIME_INFO, Capability.COMMANDS), universal.services().capabilities());
        assertDoesNotThrow(universal::close);
    }
}
