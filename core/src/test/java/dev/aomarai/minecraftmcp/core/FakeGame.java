package dev.aomarai.minecraftmcp.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** In-memory game for tests: records every call so tests can prove what did and did not reach it. */
final class FakeGame implements RuntimeInfo, PlayerReader, WorldReader, CommandRunner, RegistryReader, EntityReader,
        WorldStateReader, PermissionReader, InventoryReader, LogReader {

    final List<String> commands = new ArrayList<>();
    final List<Duration> commandTimeouts = new ArrayList<>();
    final List<BlockQuery> queries = new ArrayList<>();
    final List<RegistryQuery> registryQueries = new ArrayList<>();
    final List<EntityQuery> entityQueries = new ArrayList<>();
    final List<String> stateQueries = new ArrayList<>();
    final List<String> playerInventoryQueries = new ArrayList<>();
    final List<String> containerInventoryQueries = new ArrayList<>();
    final List<LogQuery> logQueries = new ArrayList<>();
    int permissionCalls;
    int infoCalls;
    int playerCalls;

    List<PlayerInfo> players = List.of(new PlayerInfo("Steve", "uuid-1", "minecraft:overworld", 1.5, 64.0, -2.5, true));
    WorldSnapshot snapshot = new WorldSnapshot("minecraft:overworld",
            List.of(new WorldSnapshot.BlockState(1, 2, 3, "minecraft:stone")), true);
    CommandResult commandResult = CommandResult.ok("done");
    RegistryPage registryPage = new RegistryPage("minecraft:block", List.of("minecraft:stone", "create:cogwheel"), 7, 2, true);
    EntityList entityList = new EntityList("minecraft:overworld",
            List.of(new EntityList.EntityInfo("minecraft:zombie", "uuid-2", "Zombie", 1.5, 64.0, -2.5)), true);
    WorldState worldState = new WorldState("minecraft:overworld", 12_345L, 6_000L, true, false, "normal",
            java.util.Map.of("keepInventory", "false", "doDaylightCycle", "true"));
    Permissions permissions = new Permissions(
            List.of(new Permissions.Operator("Alex", "uuid-op", 4), new Permissions.Operator("Sam", "uuid-op2", 2)),
            true, List.of(new Permissions.Whitelisted("Steve", "uuid-1")));
    InventoryContents inventory = new InventoryContents("Steve",
            List.of(new InventoryContents.Slot(0, "minecraft:diamond_sword", 1),
                    new InventoryContents.Slot(9, "create:cogwheel", 64)), false);
    LogLines log = new LogLines(List.of("[Server] Steve joined the game", "<Steve> hello"), true);
    /** When set, returned instead of a completed future, e.g. one that never completes. */
    CompletableFuture<CommandResult> pendingCommand;
    CompletableFuture<List<PlayerInfo>> pendingPlayers;
    CompletableFuture<WorldSnapshot> pendingSnapshot;
    CompletableFuture<RegistryPage> pendingRegistry;
    CompletableFuture<EntityList> pendingEntities;
    CompletableFuture<WorldState> pendingState;
    CompletableFuture<Permissions> pendingPermissions;
    CompletableFuture<InventoryContents> pendingInventory;
    CompletableFuture<LogLines> pendingLog;

    /** Every port, on the server side. */
    GameServices services() {
        return new GameServices(Side.SERVER, this);
    }

    /** Only the named capabilities, each as a view that implements exactly one port. */
    GameServices services(Side side, Capability... provided) {
        Set<Capability> wanted = provided.length == 0 ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(List.of(provided));
        List<GamePort> ports = new ArrayList<>();
        for (Capability capability : wanted) {
            ports.add(switch (capability) {
                case RUNTIME_INFO -> (RuntimeInfo) this::describe;
                case PLAYERS -> (PlayerReader) this::listPlayers;
                case WORLD -> (WorldReader) this::snapshot;
                case COMMANDS -> (CommandRunner) this::runCommand;
                case REGISTRIES -> (RegistryReader) this::list;
                case ENTITIES -> (EntityReader) this::entitiesNear;
                case WORLD_STATE -> (WorldStateReader) this::state;
                case PERMISSIONS -> (PermissionReader) this::permissions;
                case INVENTORY -> new InventoryReader() {
                    @Override
                    public CompletableFuture<InventoryContents> playerInventory(String player) {
                        return FakeGame.this.playerInventory(player);
                    }

                    @Override
                    public CompletableFuture<InventoryContents> containerInventory(String dimension, int x, int y, int z) {
                        return FakeGame.this.containerInventory(dimension, x, y, z);
                    }
                };
                case LOGS -> (LogReader) this::recentLines;
            });
        }
        return new GameServices(side, ports.toArray(new GamePort[0]));
    }

    int totalCalls() {
        return infoCalls + playerCalls + commands.size() + queries.size() + registryQueries.size() + entityQueries.size()
                + stateQueries.size() + permissionCalls + playerInventoryQueries.size()
                + containerInventoryQueries.size() + logQueries.size();
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

    @Override
    public CompletableFuture<RegistryPage> list(RegistryQuery query) {
        registryQueries.add(query);
        return pendingRegistry != null ? pendingRegistry : CompletableFuture.completedFuture(registryPage);
    }

    @Override
    public CompletableFuture<EntityList> entitiesNear(EntityQuery query) {
        entityQueries.add(query);
        return pendingEntities != null ? pendingEntities : CompletableFuture.completedFuture(entityList);
    }

    @Override
    public CompletableFuture<WorldState> state(String dimension) {
        stateQueries.add(dimension);
        return pendingState != null ? pendingState : CompletableFuture.completedFuture(worldState);
    }

    @Override
    public CompletableFuture<Permissions> permissions() {
        permissionCalls++;
        return pendingPermissions != null ? pendingPermissions : CompletableFuture.completedFuture(permissions);
    }

    @Override
    public CompletableFuture<InventoryContents> playerInventory(String player) {
        playerInventoryQueries.add(player);
        return pendingInventory != null ? pendingInventory : CompletableFuture.completedFuture(inventory);
    }

    @Override
    public CompletableFuture<InventoryContents> containerInventory(String dimension, int x, int y, int z) {
        containerInventoryQueries.add(dimension + " " + x + " " + y + " " + z);
        return pendingInventory != null ? pendingInventory : CompletableFuture.completedFuture(inventory);
    }

    @Override
    public CompletableFuture<LogLines> recentLines(LogQuery query) {
        logQueries.add(query);
        return pendingLog != null ? pendingLog : CompletableFuture.completedFuture(log);
    }
}
