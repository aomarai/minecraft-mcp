package dev.aomarai.minecraftmcp.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Read-only view of connected players. */
public interface PlayerReader extends GamePort {

    CompletableFuture<List<PlayerInfo>> listPlayers();
}
