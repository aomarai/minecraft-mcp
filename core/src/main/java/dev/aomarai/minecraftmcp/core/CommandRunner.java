package dev.aomarai.minecraftmcp.core;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Runs commands through the game's own command system so modded commands work. */
public interface CommandRunner extends GamePort {

    CompletableFuture<CommandResult> runCommand(String command, Duration timeout);
}
