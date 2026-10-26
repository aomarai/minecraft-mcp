package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Client adapters only: a look at the screen the player has open. Runs on the client's main thread. */
public interface ScreenReader extends GamePort {

    CompletableFuture<ScreenSnapshot> snapshot();
}
