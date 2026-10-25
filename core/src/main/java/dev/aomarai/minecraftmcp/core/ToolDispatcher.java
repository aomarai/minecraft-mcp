package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;

import java.time.Clock;
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
 * <p>What each tool does once allowed lives in {@link ToolHandlers}; calls wait for a port at most
 * {@link McpConfig#callTimeout()} (see {@link CallAwaiter}).</p>
 */
public final class ToolDispatcher implements ToolInvoker {

    private static final String CONFIRM = "confirm";
    private static final String COMMAND = "command";

    private final ToolPolicy policy;
    private final ToolHandlers handlers;
    private final AuditLog audit;
    private final Clock clock;

    public ToolDispatcher(McpConfig config, GameServices services, AuditLog audit) {
        this(config, services, audit, Clock.systemUTC());
    }

    ToolDispatcher(McpConfig config, GameServices services, AuditLog audit, Clock clock) {
        this.policy = new ToolPolicy(config.allowWrites(), config.allowAdmin(),
                config.allowedCommands(), config.destructiveCommands());
        this.handlers = new ToolHandlers(config, services);
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
        // Only an admin tool takes a confirmation. Any other tool ignores the argument, so it cannot be used to
        // push a destructive command through the ordinary write tool.
        boolean confirmed = tool.scope() == ToolPolicy.Scope.ADMIN && Boolean.TRUE.equals(args.get(CONFIRM));
        ToolPolicy.Decision scope = policy.checkScope(tool.scope(), confirmed);
        if (!scope.allowed() || !hasCommand(tool)) {
            return scope;
        }
        ToolPolicy.Decision command = policy.checkCommand(commandText(args), confirmed);
        if (tool.scope() == ToolPolicy.Scope.ACT && command.requiresConfirmation()) {
            return ToolPolicy.Decision.deny("this command needs admin access; use run_admin_command");
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
        ToolHandlers.Handler handler = handlers.find(tool.name())
                .orElseThrow(() -> new UnsupportedOperationException(tool.name() + " has no handler"));
        return handler.handle(new ToolArgs(args));
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
