package dev.aomarai.minecraftmcp.vanilla;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.aomarai.minecraftmcp.core.ConfigException;
import dev.aomarai.minecraftmcp.core.McpController;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code /mcp status}, {@code /mcp reload} and {@code /mcp token rotate}, for operators.
 *
 * <p>Requires vanilla permission level 4 (the console always has it). The check is made inside the
 * command rather than with {@code requires}, so a player without permission gets a clear message
 * instead of "unknown command". The work (config file I/O, restarting the endpoint) runs off the
 * game thread; the reply is sent back on it. The token is never shown: after a rotation the
 * operator reads the new one from the owner-only config file.</p>
 */
public final class McpCommand {

    private static final Logger LOG = LoggerFactory.getLogger("minecraft-mcp");

    /** The controller's work, returning the message to show. */
    private interface Action {
        String run(McpController controller) throws Exception;
    }

    private McpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<McpController> controller,
                         Executor background) {
        dispatcher.register(Commands.literal("mcp")
                .then(Commands.literal("status").executes(ctx -> run(ctx, controller, background, McpCommand::status)))
                .then(Commands.literal("reload").executes(ctx -> run(ctx, controller, background, McpCommand::reload)))
                .then(Commands.literal("token").then(Commands.literal("rotate")
                        .executes(ctx -> run(ctx, controller, background, McpCommand::rotate)))));
    }

    private static String status(McpController controller) {
        return controller.status()
                .map(status -> "MCP server is " + status.summary())
                .orElse("MCP server is not running; see the server log");
    }

    private static String reload(McpController controller) throws Exception {
        return "MCP server reloaded, now " + controller.reload().summary()
                + ". Clients reconnect; the token is unchanged.";
    }

    private static String rotate(McpController controller) throws Exception {
        controller.rotateToken();
        return "MCP token rotated. The old token no longer works. The new one is in config/minecraft-mcp.json "
                + "(it is not shown here); update your clients with it.";
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Supplier<McpController> controller,
                           Executor background, Action action) {
        CommandSourceStack source = ctx.getSource();
        if (!GameCompat.hasOperatorPermission(source)) {
            source.sendFailure(Component.literal("You do not have permission to use /mcp (operator level 4 is required)."));
            return 0;
        }
        McpController current = controller.get();
        if (current == null) {
            source.sendFailure(Component.literal("MCP is not available: the MCP server was never started on this server."));
            return 0;
        }
        background.execute(() -> {
            try {
                String message = action.run(current);
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(message), false));
            } catch (ConfigException e) {
                reply(source, "Not changed: " + e.getMessage());
            } catch (Exception e) {
                LOG.error("/mcp failed", e);
                reply(source, "Failed: " + e.getMessage() + " (see the server log)");
            }
        });
        return 1;
    }

    private static void reply(CommandSourceStack source, String message) {
        source.getServer().execute(() -> source.sendFailure(Component.literal(message)));
    }
}
