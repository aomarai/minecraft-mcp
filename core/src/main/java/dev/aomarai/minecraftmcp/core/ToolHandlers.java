package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /** A tool whose answer is an image, which the dispatcher returns as image content. */
    @FunctionalInterface
    interface ImageHandler {
        Screenshot handle() throws Exception;
    }

    private static final int DEFAULT_SCREEN_ELEMENTS = 100;

    private final McpConfig config;
    private final GameServices services;
    private final Map<String, Handler> byName;
    private final Map<String, ImageHandler> imagesByName;

    ToolHandlers(McpConfig config, GameServices services) {
        this.config = config;
        this.services = services;
        this.imagesByName = Map.of("take_screenshot", this::takeScreenshot);
        this.byName = Map.ofEntries(
                entry("server_info", args -> port(RuntimeInfo.class, "runtime info").describe()),
                entry("list_players", args -> listPlayers()),
                entry("query_blocks", this::queryBlocks),
                entry("list_registry", this::listRegistry),
                entry("list_entities", this::listEntities),
                entry("get_world_state", this::getWorldState),
                entry("list_permissions", args -> listPermissions()),
                entry("get_inventory", this::getInventory),
                entry("read_log", this::readLog),
                entry("read_events", this::readEvents),
                entry("get_screen", this::getScreen),
                entry("click_screen_element", this::clickScreenElement),
                entry("type_in_screen", this::typeInScreen),
                entry("scroll_screen", this::scrollScreen),
                entry("open_inventory", this::openInventory),
                entry("move_to", this::moveTo),
                entry("look_at", this::lookAt),
                entry("jump", args -> await(player().jump(), true)),
                entry("break_block", this::breakBlock),
                entry("place_block", this::placeBlock),
                entry("interact", this::interact),
                entry("fill_blocks", this::fillBlocks),
                entry("teleport", this::teleport),
                entry("give_item", this::giveItem),
                entry("send_message", this::sendMessage),
                entry("kick_player", this::kickPlayer),
                entry("ban_player", this::banPlayer),
                entry("save_world", args -> run(CommandBuilder.saveWorld())),
                entry("stop_server", args -> run(CommandBuilder.stopServer())),
                entry("run_command", this::runCommand),
                entry("run_admin_command", this::runCommand));
    }

    private static Map.Entry<String, Handler> entry(String name, Handler handler) {
        return Map.entry(name, handler);
    }

    Optional<Handler> find(String tool) {
        return Optional.ofNullable(byName.get(tool));
    }

    Optional<ImageHandler> findImage(String tool) {
        return Optional.ofNullable(imagesByName.get(tool));
    }

    private Screenshot takeScreenshot() throws Exception {
        ScreenshotCapture capture = port(ScreenshotCapture.class, "screenshots");
        return await(capture.capture(Screenshot.MAX_WIDTH, Screenshot.MAX_HEIGHT), false);
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
        out.put("untrusted", true);
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
        Inventory inventory = await(port(InventoryReader.class, "inventory reads")
                .inventoryOf(args.string("player")), false);
        List<Map<String, Object>> stacks = new ArrayList<>();
        for (Inventory.Stack stack : inventory.stacks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slot", stack.slot());
            item.put("item", stack.item());
            item.put("count", stack.count());
            stacks.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player", inventory.player());
        out.put("stacks", stacks);
        return json(out);
    }

    private String getScreen(ToolArgs args) throws Exception {
        String role = args.optionalString("role", "");
        String label = args.optionalString("label", "").toLowerCase(Locale.ROOT);
        int limit = args.optionalInteger("limit", DEFAULT_SCREEN_ELEMENTS);
        if (limit < 1 || limit > ScreenSnapshot.MAX_ELEMENTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + ScreenSnapshot.MAX_ELEMENTS);
        }
        List<String> roles = Arrays.stream(ScreenElement.Role.values()).map(ScreenElement.Role::json).toList();
        if (!role.isEmpty() && !roles.contains(role)) {
            throw new IllegalArgumentException("role must be one of " + roles);
        }
        ScreenSnapshot snapshot = await(port(ScreenReader.class, "screen reads").snapshot(), false);
        List<ScreenElement> matching = snapshot.elements().stream()
                .filter(e -> role.isEmpty() || e.role().json().equals(role))
                .filter(e -> e.label().toLowerCase(Locale.ROOT).contains(label))
                .toList();
        List<Map<String, Object>> elements = new ArrayList<>();
        for (ScreenElement element : matching.stream().limit(limit).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", element.id());
            item.put("role", element.role().json());
            item.put("label", element.label());
            item.put("x", element.x());
            item.put("y", element.y());
            item.put("width", element.width());
            item.put("height", element.height());
            item.put("enabled", element.enabled());
            item.put("focused", element.focused());
            elements.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // Labels and titles are written by the game and its mods. Say so in the data itself, not only in the docs.
        out.put("untrusted", true);
        out.put("screen", snapshot.screen());
        out.put("title", snapshot.title());
        out.put("overlay", snapshot.overlay());
        out.put("matching", matching.size());
        out.put("elements", elements);
        return json(out);
    }

    private String clickScreenElement(ToolArgs args) throws Exception {
        return await(port(ScreenControl.class, "screen control").click(args.string("element")), true);
    }

    private String typeInScreen(ToolArgs args) throws Exception {
        String text = args.string("text");
        if (text.isEmpty() || text.length() > ScreenControl.MAX_TYPE_LENGTH) {
            throw new IllegalArgumentException("text must be 1-" + ScreenControl.MAX_TYPE_LENGTH + " characters");
        }
        return await(port(ScreenControl.class, "screen control").type(text), true);
    }

    private String scrollScreen(ToolArgs args) throws Exception {
        int amount = args.integer("amount");
        if (amount == 0 || Math.abs(amount) > ScreenControl.MAX_SCROLL) {
            throw new IllegalArgumentException("amount must be between -" + ScreenControl.MAX_SCROLL + " and "
                    + ScreenControl.MAX_SCROLL + ", and not 0");
        }
        return await(port(ScreenControl.class, "screen control").scroll(args.optionalString("element", ""), amount), true);
    }

    private String openInventory(ToolArgs args) throws Exception {
        return await(port(ScreenControl.class, "screen control").openInventory(), true);
    }

    private PlayerController player() {
        return port(PlayerController.class, "player control");
    }

    private String moveTo(ToolArgs args) throws Exception {
        return await(player().moveTo(point(args, "x"), point(args, "y"), point(args, "z")), true);
    }

    private String lookAt(ToolArgs args) throws Exception {
        return await(player().lookAt(point(args, "x"), point(args, "y"), point(args, "z")), true);
    }

    private String breakBlock(ToolArgs args) throws Exception {
        return await(player().breakBlock(block(args, "x"), block(args, "y"), block(args, "z")), true);
    }

    private String placeBlock(ToolArgs args) throws Exception {
        return await(player().placeBlock(block(args, "x"), block(args, "y"), block(args, "z")), true);
    }

    private String interact(ToolArgs args) throws Exception {
        return await(player().interact(block(args, "x"), block(args, "y"), block(args, "z")), true);
    }

    private static double point(ToolArgs args, String name) {
        double value = args.number(name);
        if (Math.abs(value) > CommandBuilder.MAX_COORDINATE) {
            throw new IllegalArgumentException(name + " must be between -" + CommandBuilder.MAX_COORDINATE + " and "
                    + CommandBuilder.MAX_COORDINATE);
        }
        return value;
    }

    private static int block(ToolArgs args, String name) {
        int value = args.integer(name);
        if (Math.abs((long) value) > CommandBuilder.MAX_COORDINATE) {
            throw new IllegalArgumentException(name + " must be between -" + CommandBuilder.MAX_COORDINATE + " and "
                    + CommandBuilder.MAX_COORDINATE);
        }
        return value;
    }

    private String readLog(ToolArgs args) throws Exception {
        int lines = args.optionalInteger("lines", 50);
        if (lines < 1 || lines > LogReader.MAX_LINES) {
            throw new IllegalArgumentException("lines must be between 1 and " + LogReader.MAX_LINES);
        }
        List<String> tail = port(LogReader.class, "log reads").tail(lines);
        Map<String, Object> out = new LinkedHashMap<>();
        // The log contains chat and player names. Say so in the data itself, not only in the docs.
        out.put("untrusted", true);
        out.put("lines", tail.stream().map(LogRedaction::redact).toList());
        return json(out);
    }

    private String readEvents(ToolArgs args) throws Exception {
        // An in-memory read of a bounded buffer: no game state, nothing to wait for.
        EventPage page = port(EventFeed.class, "event feed")
                .read(args.optionalLong("after", -1), args.optionalInteger("limit", 50), args.optionalString("run", null));
        return EventJson.of(page, McpJsonDefaults.getMapper());
    }

    private String runCommand(ToolArgs args) throws Exception {
        return run(args.string("command"));
    }

    /**
     * The typed write tools build their command from validated arguments ({@link CommandBuilder}) and
     * run it here. They are gated by their scope, not by the command text: that is the point of
     * having a typed tool with fixed arguments.
     */
    private String fillBlocks(ToolArgs args) throws Exception {
        // BlockQuery checks that the box is well formed and measures it; the limit is not used.
        BlockQuery box = new BlockQuery(args.string("dimension"),
                args.integer("min_x"), args.integer("min_y"), args.integer("min_z"),
                args.integer("max_x"), args.integer("max_y"), args.integer("max_z"), 1);
        if (box.volume() > config.maxBlockVolume()) {
            throw new IllegalArgumentException(
                    "volume " + box.volume() + " exceeds maxBlockVolume " + config.maxBlockVolume());
        }
        String command = CommandBuilder.fill(box.dimension(), box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ(), args.string("block"));
        if (args.optionalBoolean("dry_run", false)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dryRun", true);
            out.put("volume", box.volume());
            out.put("command", command);
            return json(out);
        }
        return run(command);
    }

    private String teleport(ToolArgs args) throws Exception {
        return run(CommandBuilder.teleport(args.string("player"), args.optionalString("dimension", ""),
                args.number("x"), args.number("y"), args.number("z")));
    }

    private String giveItem(ToolArgs args) throws Exception {
        return run(CommandBuilder.give(args.string("player"), args.string("item"), args.optionalInteger("count", 1)));
    }

    private String sendMessage(ToolArgs args) throws Exception {
        return run(CommandBuilder.message(args.optionalString("player", ""), args.string("message")));
    }

    private String kickPlayer(ToolArgs args) throws Exception {
        return run(CommandBuilder.kick(args.string("player"), args.optionalString("reason", "")));
    }

    private String banPlayer(ToolArgs args) throws Exception {
        return run(CommandBuilder.ban(args.string("player"), args.optionalString("reason", "")));
    }

    private String run(String command) throws Exception {
        CommandResult result = await(port(CommandRunner.class, "command execution")
                .runCommand(command, config.callTimeout()), true);
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
