package dev.aomarai.minecraftmcp.vanilla;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.aomarai.minecraftmcp.core.CommandResult;
import dev.aomarai.minecraftmcp.core.CommandRunner;
import dev.aomarai.minecraftmcp.core.MainThread;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Runs commands through the game's own Brigadier dispatcher, so vanilla and modded commands work.
 *
 * <p>The command source is a dedicated "MCP" source, not the console and not a player, with the
 * permission level it was given. The core's policy decides what may be sent; the level here is a
 * second line of defense that stops the game itself from running admin commands unless admin
 * access is enabled.</p>
 */
public final class VanillaCommandRunner implements CommandRunner {

    private final MinecraftServer server;
    private final int permissionLevel;

    public VanillaCommandRunner(MinecraftServer server, int permissionLevel) {
        this.server = server;
        this.permissionLevel = permissionLevel;
    }

    @Override
    public CompletableFuture<CommandResult> runCommand(String command, Duration timeout) {
        return MainThread.call(server, () -> run(command));
    }

    private CommandResult run(String command) {
        Collector output = new Collector();
        CommandSourceStack source = GameCompat.commandSource(output, server, permissionLevel);
        String line = command.startsWith("/") ? command.substring(1) : command;
        try {
            server.getCommands().getDispatcher().execute(line, source);
            return CommandResult.ok(output.text());
        } catch (CommandSyntaxException e) {
            return CommandResult.error(e.getMessage());
        }
    }

    /** Collects what the command says, successes and failures alike, and stops once there is more than a result keeps. */
    private static final class Collector implements CommandSource {
        private final StringBuilder lines = new StringBuilder();
        private boolean any;

        String text() {
            return lines.toString();
        }

        @Override
        public void sendSystemMessage(Component message) {
            if (lines.length() <= CommandResult.MAX_OUTPUT) {
                lines.append(any ? "\n" : "").append(message.getString());
                any = true;
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
