package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs catalog tools behind the policy gate.
 *
 * <p>Order of events for every call: the policy decides, the decision is
 * written to the {@link AuditLog}, and only an allowed call reaches a game
 * port. A denied call, a call that still needs confirmation, and a call that
 * cannot be audited never touch the game. After an allowed call the outcome is
 * audited too.</p>
 *
 * <p>Policy is independent of Minecraft permissions: the AI does not inherit
 * the operator's OP. Destructive commands through {@code run_command} are
 * refused and must use {@code run_admin_command} with {@code confirm=true}.</p>
 *
 * <p>Calls wait for a port at most {@link McpConfig#callTimeout()}; see
 * {@link CallAwaiter} for timeout, cancellation and side-effect semantics.</p>
 */
public final class ToolDispatcher implements ToolInvoker {

    private static final String CONFIRM = "confirm";
    private static final String COMMAND = "command";

    private final McpConfig config;
    private final ToolPolicy policy;
    private final GameServices services;
    private final AuditLog audit;
    private final Clock clock;

    public ToolDispatcher(McpConfig config, GameServices services, AuditLog audit) {
        this(config, services, audit, Clock.systemUTC());
    }

    ToolDispatcher(McpConfig config, GameServices services, AuditLog audit, Clock clock) {
        this.config = config;
        this.policy = new ToolPolicy(config.allowWrites(), config.allowAdmin());
        this.services = services;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public McpSchema.CallToolResult call(ToolDescriptor tool, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        ToolPolicy.Decision decision = decide(tool, args);
        String verdict = verdict(decision);

        try {
            record(AuditEntry.DECISION, tool, args, verdict, decision.reason());
        } catch (RuntimeException e) {
            return error("audit log unavailable; call refused");
        }
        if (!decision.allowed()) {
            return error(decision.reason());
        }
        if (decision.requiresConfirmation()) {
            return error(decision.reason() + "; call again with confirm=true");
        }

        String output;
        boolean failed;
        try {
            output = execute(tool, args);
            failed = false;
        } catch (Exception e) {
            output = describe(e);
            failed = true;
        }
        try {
            record(AuditEntry.RESULT, tool, args, "allow", (failed ? "error: " : "ok: ") + output);
        } catch (RuntimeException e) {
            return error("audit log unavailable after the call ran; result: " + output);
        }
        return failed ? error(output) : ok(output);
    }

    private static String verdict(ToolPolicy.Decision decision) {
        if (!decision.allowed()) {
            return "deny";
        }
        return decision.requiresConfirmation() ? "confirm" : "allow";
    }

    private ToolPolicy.Decision decide(ToolDescriptor tool, Map<String, Object> args) {
        boolean confirmed = Boolean.TRUE.equals(args.get(CONFIRM));
        ToolPolicy.Decision scope = policy.checkScope(tool.scope(), confirmed);
        if (!scope.allowed() || !hasCommand(tool)) {
            return scope;
        }
        ToolPolicy.Decision command = policy.checkCommand(commandText(args), confirmed);
        if (tool.scope() == ToolPolicy.Scope.ACT && command.requiresConfirmation()) {
            return ToolPolicy.Decision.deny("destructive commands must use run_admin_command");
        }
        if (!command.allowed()) {
            return command;
        }
        // Either the scope still needs confirmation, or both checks allowed the call.
        return scope;
    }

    private static boolean hasCommand(ToolDescriptor tool) {
        Map<String, Object> properties = tool.inputSchema().properties();
        return properties != null && properties.containsKey(COMMAND);
    }

    private static String commandText(Map<String, Object> args) {
        return args.get(COMMAND) instanceof String text ? text : null;
    }

    private void record(String phase, ToolDescriptor tool, Map<String, Object> args, String decision,
                        String detail) {
        audit.record(new AuditEntry(clock.instant(), phase, tool.name(), args, decision, detail));
    }

    private String execute(ToolDescriptor tool, Map<String, Object> args) throws Exception {
        return switch (tool.name()) {
            case "server_info" -> require(services.info(), "runtime info").describe();
            case "list_players" -> listPlayers();
            case "query_blocks" -> queryBlocks(args);
            case "run_command", "run_admin_command" -> runCommand(args);
            default -> throw new UnsupportedOperationException(tool.name() + " has no handler");
        };
    }

    private String listPlayers() throws Exception {
        List<PlayerInfo> players = CallAwaiter.await(
                require(services.players(), "player listing").listPlayers(), config.callTimeout(), false);
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

    private String queryBlocks(Map<String, Object> args) throws Exception {
        BlockQuery query = new BlockQuery(
                stringArg(args, "dimension"),
                intArg(args, "min_x"), intArg(args, "min_y"), intArg(args, "min_z"),
                intArg(args, "max_x"), intArg(args, "max_y"), intArg(args, "max_z"),
                intArg(args, "limit"));
        if (query.volume() > config.maxBlockVolume()) {
            throw new IllegalArgumentException(
                    "volume " + query.volume() + " exceeds maxBlockVolume " + config.maxBlockVolume());
        }
        WorldSnapshot snapshot = CallAwaiter.await(
                require(services.world(), "block queries").snapshot(query), config.callTimeout(), false);
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

    private String runCommand(Map<String, Object> args) throws Exception {
        CommandResult result = CallAwaiter.await(require(services.commands(), "command execution")
                .runCommand(commandText(args), config.callTimeout()), config.callTimeout(), true);
        if (!result.success()) {
            throw new IllegalStateException(String.join("; ", result.feedback()));
        }
        return result.output();
    }

    private static <T> T require(T port, String what) {
        if (port == null) {
            throw new UnsupportedOperationException(what + " is not supported by this adapter");
        }
        return port;
    }

    private static String stringArg(Map<String, Object> args, String name) {
        if (!(args.get(name) instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return text;
    }

    private static int intArg(Map<String, Object> args, String name) {
        if (!(args.get(name) instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return number.intValue();
    }

    private static String json(Object value) throws Exception {
        return McpJsonDefaults.getMapper().writeValueAsString(value);
    }

    private static String describe(Throwable t) {
        return Objects.toString(t.getMessage(), t.getClass().getSimpleName());
    }

    private static McpSchema.CallToolResult ok(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    private static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(true).build();
    }
}
