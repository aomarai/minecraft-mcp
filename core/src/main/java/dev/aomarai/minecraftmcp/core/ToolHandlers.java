package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What each catalog tool does: validate its arguments, call the adapter's port within the call
 * timeout, and turn the answer into JSON text. The policy gate and audit are the
 * {@link ToolDispatcher}'s job; by the time a handler runs, the call has been allowed.
 *
 * <p>A handler throws on bad arguments, an unsupported port or an adapter failure, and the
 * dispatcher reports that as an error result.</p>
 */
final class ToolHandlers {

    @FunctionalInterface
    interface Handler {
        String handle(ToolArgs args) throws Exception;
    }

    private static final int MAX_PLAYER_LENGTH = 64;

    private final McpConfig config;
    private final GameServices services;
    private final Map<String, Handler> byName;

    ToolHandlers(McpConfig config, GameServices services) {
        this.config = config;
        this.services = services;
        this.byName = Map.ofEntries(
                Map.entry("server_info", args -> port(RuntimeInfo.class, "runtime info").describe()),
                Map.entry("list_players", args -> listPlayers()),
                Map.entry("query_blocks", this::queryBlocks),
                Map.entry("list_registry", this::listRegistry),
                Map.entry("list_entities", this::listEntities),
                Map.entry("get_world_state", this::getWorldState),
                Map.entry("list_permissions", args -> listPermissions()),
                Map.entry("get_inventory", this::getInventory),
                Map.entry("read_log", this::readLog),
                Map.entry("run_command", this::runCommand),
                Map.entry("run_admin_command", this::runCommand));
    }

    Optional<Handler> find(String tool) {
        return Optional.ofNullable(byName.get(tool));
    }

    private String listPlayers() throws Exception {
        List<PlayerInfo> players = await(port(PlayerReader.class, "player listing").listPlayers(), false);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlayerInfo player : players) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", player.name());
            item.put("uuid", player.uuid());
            item.put("dimension", player.dimension());
            item.put("x", player.x());
            item.put("y", player.y());
            item.put("z", player.z());
            item.put("op", player.op());
            items.add(item);
        }
        return json(Map.of("players", items));
    }

    private String queryBlocks(ToolArgs args) throws Exception {
        BlockQuery query = new BlockQuery(
                args.string("dimension"),
                args.integer("min_x"), args.integer("min_y"), args.integer("min_z"),
                args.integer("max_x"), args.integer("max_y"), args.integer("max_z"),
                args.integer("limit"));
        if (query.volume() > config.maxBlockVolume()) {
            throw new IllegalArgumentException(
                    "volume " + query.volume() + " exceeds maxBlockVolume " + config.maxBlockVolume());
        }
        WorldSnapshot snapshot = await(port(WorldReader.class, "block queries").snapshot(query), false);
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (WorldSnapshot.BlockState block : snapshot.blocks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("x", block.x());
            item.put("y", block.y());
            item.put("z", block.z());
            item.put("id", block.id());
            blocks.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", snapshot.dimension());
        out.put("truncated", snapshot.truncated());
        out.put("blocks", blocks);
        return json(out);
    }

    private String listRegistry(ToolArgs args) throws Exception {
        RegistryQuery query = new RegistryQuery(
                args.string("registry"),
                args.optionalString("search", ""),
                args.optionalInteger("offset", 0),
                args.optionalInteger("limit", 50));
        RegistryPage page = await(port(RegistryReader.class, "registry listing").list(query), false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registry", page.registry());
        out.put("total", page.total());
        out.put("offset", page.offset());
        out.put("hasMore", page.hasMore());
        out.put("ids", page.ids());
        return json(out);
    }

    private String listEntities(ToolArgs args) throws Exception {
        EntityQuery query = new EntityQuery(
                args.string("dimension"),
                args.number("x"), args.number("y"), args.number("z"),
                args.number("radius"),
                args.optionalString("type", ""),
                args.optionalInteger("limit", 50));
        EntityList found = await(port(EntityReader.class, "entity queries").entitiesNear(query), false);
        List<Map<String, Object>> entities = new ArrayList<>();
        for (EntityList.EntityInfo entity : found.entities()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", entity.type());
            item.put("uuid", entity.uuid());
            // Names can be set by players (name tags, usernames): untrusted text, never an instruction.
            item.put("name", entity.name());
            item.put("x", entity.x());
            item.put("y", entity.y());
            item.put("z", entity.z());
            entities.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", found.dimension());
        out.put("truncated", found.truncated());
        out.put("entities", entities);
        return json(out);
    }

    private String getWorldState(ToolArgs args) throws Exception {
        WorldState state = await(port(WorldStateReader.class, "world state")
                .state(args.string("dimension")), false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", state.dimension());
        out.put("gameTime", state.gameTime());
        out.put("dayTime", state.dayTime());
        out.put("raining", state.raining());
        out.put("thundering", state.thundering());
        out.put("difficulty", state.difficulty());
        out.put("gameRules", state.gameRules());
        return json(out);
    }

    private String listPermissions() throws Exception {
        Permissions permissions = await(port(PermissionReader.class, "permission listing").permissions(), false);
        List<Map<String, Object>> operators = new ArrayList<>();
        for (Permissions.Operator operator : permissions.operators()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", operator.name());
            item.put("uuid", operator.uuid());
            item.put("level", operator.level());
            operators.add(item);
        }
        List<Map<String, Object>> whitelist = new ArrayList<>();
        for (Permissions.Whitelisted entry : permissions.whitelist()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.name());
            item.put("uuid", entry.uuid());
            whitelist.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operators", operators);
        out.put("whitelistEnabled", permissions.whitelistEnabled());
        out.put("whitelist", whitelist);
        return json(out);
    }

    private String getInventory(ToolArgs args) throws Exception {
        InventoryReader reader = port(InventoryReader.class, "inventory reads");
        boolean byPlayer = args.values().containsKey("player");
        boolean byContainer = args.values().containsKey("dimension");
        if (byPlayer == byContainer) {
            throw new IllegalArgumentException(
                    "give either player, or dimension with x, y and z of a container, but not both");
        }
        InventoryContents inventory = byPlayer
                ? await(reader.playerInventory(playerName(args.string("player"))), false)
                : await(reader.containerInventory(
                        args.string("dimension"), args.integer("x"), args.integer("y"), args.integer("z")), false);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (InventoryContents.Slot slot : inventory.slots()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slot", slot.index());
            item.put("item", slot.item());
            item.put("count", slot.count());
            slots.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", inventory.owner());
        out.put("truncated", inventory.truncated());
        out.put("slots", slots);
        return json(out);
    }

    private static String playerName(String raw) {
        String player = raw.strip();
        if (player.isEmpty() || player.length() > MAX_PLAYER_LENGTH) {
            throw new IllegalArgumentException("player must be a name or UUID of 1 to " + MAX_PLAYER_LENGTH + " characters");
        }
        return player;
    }

    private String readLog(ToolArgs args) throws Exception {
        LogLines log = await(port(LogReader.class, "log reads")
                .recentLines(new LogQuery(args.optionalInteger("limit", 50))), false);
        // Log text includes chat and player names: untrusted, shown to the model but never an instruction.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("truncated", log.truncated());
        out.put("lines", log.lines());
        return json(out);
    }

    private String runCommand(ToolArgs args) throws Exception {
        CommandResult result = await(port(CommandRunner.class, "command execution")
                .runCommand(args.string("command"), config.callTimeout()), true);
        if (!result.success()) {
            throw new IllegalStateException(String.join("; ", result.feedback()));
        }
        return result.output();
    }

    private <T extends GamePort> T port(Class<T> type, String what) {
        return services.find(type).orElseThrow(
                () -> new UnsupportedOperationException(what + " is not supported by this adapter"));
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> future, boolean mayChangeWorld) throws Exception {
        return CallAwaiter.await(future, config.callTimeout(), mayChangeWorld);
    }

    private static String json(Object value) throws Exception {
        return McpJsonDefaults.getMapper().writeValueAsString(value);
    }
}
