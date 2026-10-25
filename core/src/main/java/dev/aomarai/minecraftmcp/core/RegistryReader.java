package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only listing of the game's registries (blocks, items, entity types, dimensions, ...), mods included. */
public interface RegistryReader extends GamePort {

    CompletableFuture<RegistryPage> list(RegistryQuery query);
}
