package dev.aomarai.minecraftmcp.fabric;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.aomarai.minecraftmcp.core.CommandResult;
import dev.aomarai.minecraftmcp.core.CommandRunner;
import dev.aomarai.minecraftmcp.core.MainThread;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Runs commands through the game's own Brigadier dispatcher, so vanilla and modded commands work.
 *
 * <p>The command source is a dedicated "MCP" source, not the console and not a player, with the
 * permission level it was given. The core's policy decides what may be sent; the level here is a
 * second line of defense that stops the game itself from running admin commands unless admin
 * access is enabled.</p>
 */
final class FabricCommandRunner implements CommandRunner {

    private final MinecraftServer server;
    private final int permissionLevel;

    FabricCommandRunner(MinecraftServer server, int permissionLevel) {
        this.server = server;
        this.permissionLevel = permissionLevel;
    }

    @Override
    public CompletableFuture<CommandResult> runCommand(String command, Duration timeout) {
        return MainThread.call(server, () -> run(command));
    }

    private CommandResult run(String command) {
        List<String> output = new ArrayList<>();
        CommandSourceStack source = new CommandSourceStack(new Collector(output), Vec3.ZERO, Vec2.ZERO,
                server.overworld(), permissionLevel, "MCP", Component.literal("MCP"), server, null);
        String line = command.startsWith("/") ? command.substring(1) : command;
        try {
            server.getCommands().getDispatcher().execute(line, source);
            return CommandResult.ok(String.join("\n", output));
        } catch (CommandSyntaxException e) {
            return CommandResult.error(e.getMessage());
        }
    }

    /** Collects what the command says, successes and failures alike. */
    private record Collector(List<String> lines) implements CommandSource {
        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
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
