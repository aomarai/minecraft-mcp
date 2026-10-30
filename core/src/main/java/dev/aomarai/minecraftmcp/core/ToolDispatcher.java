package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.spec.McpSchema;

import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

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
 * <p>At most {@value #MAX_IN_FLIGHT} calls run at once on this dispatcher. HTTP workers can queue work on the game
 * thread, so a client (or a model stuck in a loop) could stack many main-thread calls for one tick. A call over the
 * limit is refused at once, without waiting, and the refusal is audited like any other denial.</p>
 *
 * <p>What each tool does once allowed lives in {@link ToolHandlers}; calls wait for a port at most
 * {@link McpConfig#callTimeout()} (see {@link CallAwaiter}).</p>
 */
public final class ToolDispatcher implements ToolInvoker {

    private static final System.Logger LOG = System.getLogger(ToolDispatcher.class.getName());
    /** How many calls may be in progress at once; the next one is refused until one finishes. */
    static final int MAX_IN_FLIGHT = 16;
    static final String BUSY = "too many tool calls are in progress (at most " + MAX_IN_FLIGHT
            + " at once); try again shortly";
    private static final String CONFIRM = "confirm";
    private static final String COMMAND = "command";

    private final ToolPolicy policy;
    private final ToolHandlers handlers;
    private final AuditLog audit;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    public ToolDispatcher(McpConfig config, GameServices services, AuditLog audit) {
        this(config, services, audit, Clock.systemUTC(), System::nanoTime);
    }

    ToolDispatcher(McpConfig config, GameServices services, AuditLog audit, Clock clock) {
        this(config, services, audit, clock, System::nanoTime);
    }

    ToolDispatcher(McpConfig config, GameServices services, AuditLog audit, Clock clock, LongSupplier nanos) {
        this.nanos = nanos;
        this.policy = new ToolPolicy(config.allowWrites(), config.allowAdmin(),
                config.allowedCommands(), config.destructiveCommands());
        this.handlers = new ToolHandlers(config, services);
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public McpSchema.CallToolResult call(ToolDescriptor tool, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        if (!inFlight.tryAcquire()) {
            return refuseBusy(tool, args);
        }
        CallTiming timing = CallTiming.begin(nanos);
        try {
            return call(tool, args, timing);
        } finally {
            CallTiming.end();
            inFlight.release();
        }
    }

    /** Refused without waiting or touching the game; the refusal is audited, and is refused all the same if it cannot be. */
    private McpSchema.CallToolResult refuseBusy(ToolDescriptor tool, Map<String, Object> arguments) {
        try {
            append(AuditEntry.PHASE_DECISION, tool, arguments, "deny", BUSY);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot audit a call refused as busy: {0}", e.toString());
        }
        return error(BUSY);
    }

    private McpSchema.CallToolResult call(ToolDescriptor tool, Map<String, Object> args, CallTiming timing) {
        ToolPolicy.Decision decision = decide(tool, args);
        String verdict = verdict(decision);

        try {
            append(AuditEntry.PHASE_DECISION, tool, args, verdict, decision.reason());
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
        Screenshot image = null;
        boolean failed;
        try {
            Outcome outcome = execute(tool, args);
            output = outcome.text();
            image = outcome.image();
            failed = false;
        } catch (Exception e) {
            output = describe(e);
            failed = true;
        }
        CallTiming.Result took = timing.finish();
        if (took.overrun()) {
            LOG.log(System.Logger.Level.WARNING, "{0} held the game thread for {1} ms; a tick is {2} ms",
                    tool.name(), took.mainThreadNanos() / 1_000_000, CallTiming.BUDGET.toMillis());
        }
        try {
            audit.record(new AuditEntry(clock.instant(), AuditEntry.PHASE_RESULT, tool.name(), args, "allow",
                    (failed ? "error: " : "ok: ") + output, took));
        } catch (RuntimeException e) {
            return error("audit log unavailable after the call ran; result: " + output);
        }
        return failed ? error(output) : ok(output, image);
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

    private void append(String phase, ToolDescriptor tool, Map<String, Object> args, String decision,
                        String detail) {
        audit.record(new AuditEntry(clock.instant(), phase, tool.name(), args, decision, detail));
    }

    /** What a handler produced: text, or an image with the text the audit log keeps in its place. */
    private record Outcome(String text, Screenshot image) {
    }

    private Outcome execute(ToolDescriptor tool, Map<String, Object> args) throws Exception {
        Optional<ToolHandlers.ImageHandler> imageHandler = handlers.findImage(tool.name());
        if (imageHandler.isPresent()) {
            Screenshot image = imageHandler.get().handle();
            return new Outcome(image.describe(), image);
        }
        ToolHandlers.Handler handler = handlers.find(tool.name())
                .orElseThrow(() -> new UnsupportedOperationException(tool.name() + " has no handler"));
        return new Outcome(handler.handle(new ToolArgs(args)), null);
    }

    private static String describe(Throwable t) {
        return Objects.toString(t.getMessage(), t.getClass().getSimpleName());
    }

    private static McpSchema.CallToolResult ok(String text, Screenshot image) {
        McpSchema.CallToolResult.Builder result = McpSchema.CallToolResult.builder().isError(false);
        if (image == null) {
            return result.addTextContent(text).build();
        }
        String data = Base64.getEncoder().encodeToString(image.png());
        return result.addContent(new McpSchema.ImageContent(null, data, "image/png")).build();
    }

    private static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(true).build();
    }
}
