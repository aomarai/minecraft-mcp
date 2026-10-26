package dev.aomarai.minecraftmcp.core;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** In-memory game for tests: records every call so tests can prove what did and did not reach it. */
final class FakeGame implements RuntimeInfo, PlayerReader, WorldReader, CommandRunner, RegistryReader, EntityReader,
        WorldStateReader, PermissionReader, InventoryReader, LogReader, EventFeed, ScreenReader, ScreenControl, PlayerController {

    final List<String> commands = new ArrayList<>();
    final List<Duration> commandTimeouts = new ArrayList<>();
    final List<BlockQuery> queries = new ArrayList<>();
    final List<RegistryQuery> registryQueries = new ArrayList<>();
    final List<EntityQuery> entityQueries = new ArrayList<>();
    final List<String> stateQueries = new ArrayList<>();
    final List<String> inventoryQueries = new ArrayList<>();
    final List<Integer> logQueries = new ArrayList<>();
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
    Inventory inventory = new Inventory("Steve", List.of(
            new Inventory.Stack(0, "minecraft:diamond_sword", 1), new Inventory.Stack(36, "create:cogwheel", 64)));
    List<String> logLines = List.of("[12:00:00] [Server thread/INFO]: Done (1.2s)!",
            "[12:00:01] [Server thread/INFO]: Steve[/192.168.1.5:54321] logged in", "<Steve> hello");
    IOException logFailure;
    final EventBuffer events = new EventBuffer(100);
    final List<String> eventReads = new ArrayList<>();
    /** When set, returned instead of a completed future, e.g. one that never completes. */
    CompletableFuture<CommandResult> pendingCommand;
    CompletableFuture<List<PlayerInfo>> pendingPlayers;
    CompletableFuture<WorldSnapshot> pendingSnapshot;
    CompletableFuture<RegistryPage> pendingRegistry;
    CompletableFuture<EntityList> pendingEntities;
    CompletableFuture<WorldState> pendingState;
    CompletableFuture<Permissions> pendingPermissions;
    CompletableFuture<Inventory> pendingInventory;
    final List<int[]> screenshotRequests = new ArrayList<>();
    int screenReads;
    final List<String> screenActions = new ArrayList<>();
    ScreenSnapshot screen = new ScreenSnapshot("TitleScreen", "Title Screen", "", List.of(
            new ScreenElement("0:button", ScreenElement.Role.BUTTON, "Singleplayer", 10, 20, 200, 20, true, false),
            new ScreenElement("1:button", ScreenElement.Role.BUTTON, "Options...", 10, 50, 98, 20, true, true),
            new ScreenElement("2:text_field", ScreenElement.Role.TEXT_FIELD, "Search", 10, 80, 200, 20, false, false)));
    final List<String> playerActions = new ArrayList<>();
    CompletableFuture<String> pendingPlayerAction;
    CompletableFuture<ScreenSnapshot> pendingScreen;
    CompletableFuture<String> pendingScreenAction;
    Screenshot screenshot = new Screenshot(png(), 2, 3);
    CompletableFuture<Screenshot> pendingScreenshot;

    /** The smallest thing that starts like a PNG file; the core checks the signature and the bounds only. */
    static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};
    }

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
                case INVENTORIES -> (InventoryReader) this::inventoryOf;
                case LOGS -> (LogReader) this::tail;
                case EVENTS -> new EventFeed() {
                    @Override
                    public EventPage read(long after, int limit) {
                        return FakeGame.this.read(after, limit);
                    }

                    @Override
                    public Runnable onPublish(Runnable listener) {
                        return FakeGame.this.onPublish(listener);
                    }
                };
                case SCREENSHOTS -> (ScreenshotCapture) this::capture;
                case PLAYER_CONTROL -> new PlayerController() {
                    @Override
                    public CompletableFuture<String> moveTo(double x, double y, double z) {
                        return FakeGame.this.moveTo(x, y, z);
                    }

                    @Override
                    public CompletableFuture<String> lookAt(double x, double y, double z) {
                        return FakeGame.this.lookAt(x, y, z);
                    }

                    @Override
                    public CompletableFuture<String> jump() {
                        return FakeGame.this.jump();
                    }

                    @Override
                    public CompletableFuture<String> breakBlock(int x, int y, int z) {
                        return FakeGame.this.breakBlock(x, y, z);
                    }

                    @Override
                    public CompletableFuture<String> placeBlock(int x, int y, int z) {
                        return FakeGame.this.placeBlock(x, y, z);
                    }

                    @Override
                    public CompletableFuture<String> interact(int x, int y, int z) {
                        return FakeGame.this.interact(x, y, z);
                    }
                };
                case SCREENS -> (ScreenReader) this::snapshot;
                case SCREEN_CONTROL -> new ScreenControl() {
                    @Override
                    public CompletableFuture<String> click(String element) {
                        return FakeGame.this.click(element);
                    }

                    @Override
                    public CompletableFuture<String> type(String text) {
                        return FakeGame.this.type(text);
                    }

                    @Override
                    public CompletableFuture<String> scroll(String element, int notches) {
                        return FakeGame.this.scroll(element, notches);
                    }

                    @Override
                    public CompletableFuture<String> openInventory() {
                        return FakeGame.this.openInventory();
                    }
                };
            });
        }
        return new GameServices(side, ports.toArray(new GamePort[0]));
    }

    @Override
    public CompletableFuture<ScreenSnapshot> snapshot() {
        screenReads++;
        return pendingScreen != null ? pendingScreen : CompletableFuture.completedFuture(screen);
    }

    @Override
    public CompletableFuture<String> click(String element) {
        return act("click " + element, "clicked " + element);
    }

    @Override
    public CompletableFuture<String> type(String text) {
        return act("type " + text, "typed " + text.length() + " characters");
    }

    @Override
    public CompletableFuture<String> scroll(String element, int notches) {
        return act("scroll " + element + " " + notches, "scrolled " + notches);
    }

    @Override
    public CompletableFuture<String> openInventory() {
        return act("open_inventory", "opened the inventory");
    }

    @Override
    public CompletableFuture<String> moveTo(double x, double y, double z) {
        return player("move_to " + x + " " + y + " " + z, "moved");
    }

    @Override
    public CompletableFuture<String> lookAt(double x, double y, double z) {
        return player("look_at " + x + " " + y + " " + z, "looked");
    }

    @Override
    public CompletableFuture<String> jump() {
        return player("jump", "jumped");
    }

    @Override
    public CompletableFuture<String> breakBlock(int x, int y, int z) {
        return player("break_block " + x + " " + y + " " + z, "broke");
    }

    @Override
    public CompletableFuture<String> placeBlock(int x, int y, int z) {
        return player("place_block " + x + " " + y + " " + z, "placed");
    }

    @Override
    public CompletableFuture<String> interact(int x, int y, int z) {
        return player("interact " + x + " " + y + " " + z, "interacted");
    }

    private CompletableFuture<String> player(String call, String result) {
        playerActions.add(call);
        return pendingPlayerAction != null ? pendingPlayerAction : CompletableFuture.completedFuture(result);
    }

    private CompletableFuture<String> act(String call, String result) {
        screenActions.add(call);
        return pendingScreenAction != null ? pendingScreenAction : CompletableFuture.completedFuture(result);
    }

    private CompletableFuture<Screenshot> capture(int maxWidth, int maxHeight) {
        screenshotRequests.add(new int[]{maxWidth, maxHeight});
        return pendingScreenshot != null ? pendingScreenshot : CompletableFuture.completedFuture(screenshot);
    }

    int totalCalls() {
        return screenshotRequests.size() + screenReads + screenActions.size() + playerActions.size() + infoCalls + playerCalls + commands.size() + queries.size() + registryQueries.size() + entityQueries.size()
                + stateQueries.size() + permissionCalls + inventoryQueries.size() + logQueries.size() + eventReads.size();
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
    public CompletableFuture<Inventory> inventoryOf(String player) {
        inventoryQueries.add(player);
        return pendingInventory != null ? pendingInventory : CompletableFuture.completedFuture(inventory);
    }

    @Override
    public EventPage read(long after, int limit) {
        eventReads.add(after + "," + limit);
        return events.read(after, limit);
    }

    @Override
    public Runnable onPublish(Runnable listener) {
        return events.onPublish(listener);
    }

    @Override
    public List<String> tail(int lines) throws IOException {
        logQueries.add(lines);
        if (logFailure != null) {
            throw logFailure;
        }
        return logLines;
    }
}
