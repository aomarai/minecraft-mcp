package dev.aomarai.minecraftmcp.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.aomarai.minecraftmcp.core.ConfigException;
import dev.aomarai.minecraftmcp.core.McpController;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code /mcpclient status}, {@code /mcpclient reload} and {@code /mcpclient token rotate}: what {@code /mcp} does
 * for the server endpoint, for the client's own endpoint ({@code minecraft-mcp-client.json}). A client command runs
 * for the person at the keyboard, so there is no permission check. The work (config file I/O, restarting the
 * endpoint) runs off the client thread and the reply is sent back on it. The token is never shown: after a rotation
 * it is read from the owner-only config file.
 */
final class McpClientCommand {

    private static final Logger LOG = LoggerFactory.getLogger("minecraft-mcp");

    /** The controller's work, returning the message to show. */
    private interface Action {
        String run(McpController controller) throws Exception;
    }

    private McpClientCommand() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Supplier<McpController> controller,
                         Executor background) {
        dispatcher.register(literal("mcpclient")
                .then(literal("status")
                        .executes(ctx -> run(ctx, controller, background, McpClientCommand::status)))
                .then(literal("reload")
                        .executes(ctx -> run(ctx, controller, background, McpClientCommand::reload)))
                .then(literal("token").then(literal("rotate")
                        .executes(ctx -> run(ctx, controller, background, McpClientCommand::rotate)))));
    }

    /** Brigadier's own builder, because the Fabric helper that wraps it has a different name on 26.x. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static String status(McpController controller) {
        return controller.status()
                .map(status -> "MCP client server is " + status.summary())
                .orElse("MCP client server is not running; see the game log");
    }

    private static String reload(McpController controller) throws Exception {
        return "MCP client server reloaded, now " + controller.reload().summary()
                + ". Clients reconnect; the token is unchanged.";
    }

    private static String rotate(McpController controller) throws Exception {
        controller.rotateToken();
        return "MCP client token rotated. The old token no longer works. The new one is in "
                + "config/minecraft-mcp-client.json (it is not shown here); update your clients with it.";
    }

    private static int run(CommandContext<FabricClientCommandSource> ctx, Supplier<McpController> controller,
                           Executor background, Action action) {
        FabricClientCommandSource source = ctx.getSource();
        McpController current = controller.get();
        if (current == null) {
            source.sendError(Component.literal("MCP is not available: the client endpoint was never started."));
            return 0;
        }
        background.execute(() -> {
            try {
                String message = action.run(current);
                source.getClient().execute(() -> source.sendFeedback(Component.literal(message)));
            } catch (ConfigException e) {
                error(source, "Not changed: " + e.getMessage());
            } catch (Exception e) {
                LOG.error("/mcpclient failed", e);
                error(source, "Failed: " + e.getMessage() + " (see the game log)");
            }
        });
        return 1;
    }

    private static void error(FabricClientCommandSource source, String message) {
        source.getClient().execute(() -> source.sendError(Component.literal(message)));
    }
}
