package dev.aomarai.minecraftmcp.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** In-memory game for tests: records every call so tests can prove what did and did not reach it. */
final class FakeGame implements RuntimeInfo, PlayerReader, WorldReader, CommandRunner {

    final List<String> commands = new ArrayList<>();
    final List<Duration> commandTimeouts = new ArrayList<>();
    final List<BlockQuery> queries = new ArrayList<>();
    int infoCalls;
    int playerCalls;

    List<PlayerInfo> players = List.of(new PlayerInfo("Steve", "uuid-1", "minecraft:overworld", 1.5, 64.0, -2.5, true));
    WorldSnapshot snapshot = new WorldSnapshot("minecraft:overworld",
            List.of(new WorldSnapshot.BlockState(1, 2, 3, "minecraft:stone")), true);
    CommandResult commandResult = CommandResult.ok("done");
    /** When set, returned instead of a completed future, e.g. one that never completes. */
    CompletableFuture<CommandResult> pendingCommand;
    CompletableFuture<List<PlayerInfo>> pendingPlayers;
    CompletableFuture<WorldSnapshot> pendingSnapshot;

    GameServices services() {
        return new GameServices(this, this, this, this, Side.SERVER);
    }

    int totalCalls() {
        return infoCalls + playerCalls + commands.size() + queries.size();
    }

    @Override
    public String describe() {
        infoCalls++;
        return "Fake 1.0 / Minecraft test / server";
    }

    @Override
    public CompletableFuture<List<PlayerInfo>> listPlayers() {
        playerCalls++;
        return pendingPlayers != null ? pendingPlayers : CompletableFuture.completedFuture(players);
    }

    @Override
    public CompletableFuture<WorldSnapshot> snapshot(BlockQuery query) {
        queries.add(query);
        return pendingSnapshot != null ? pendingSnapshot : CompletableFuture.completedFuture(snapshot);
    }

    @Override
    public CompletableFuture<CommandResult> runCommand(String command, Duration timeout) {
        commands.add(command);
        commandTimeouts.add(timeout);
        return pendingCommand != null ? pendingCommand : CompletableFuture.completedFuture(commandResult);
    }
}
