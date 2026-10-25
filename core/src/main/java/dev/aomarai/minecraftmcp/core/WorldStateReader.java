package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only view of a dimension's time, weather, difficulty and game rules. */
public interface WorldStateReader extends GamePort {

    CompletableFuture<WorldState> state(String dimension);
}
