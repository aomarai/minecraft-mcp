package dev.aomarai.minecraftmcp.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The single port between the loader-agnostic MCP core and a Minecraft runtime.
 *
 * <p>Implementations must marshal every world read and write onto the game's main
 * thread. The core may call these methods from HTTP worker threads.</p>
 */
public interface GameAccess {

    CompletableFuture<CommandResult> runCommand(String command, Duration timeout);

    CompletableFuture<List<PlayerInfo>> listPlayers();

    CompletableFuture<WorldSnapshot> snapshot(BlockQuery query);

    /** Human-readable runtime description, e.g. "Fabric 0.19.5 / Minecraft 26.2 / server". */
    String describe();
}
