package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only, bounded view of entities in loaded chunks. */
public interface EntityReader extends GamePort {

    CompletableFuture<EntityList> entitiesNear(EntityQuery query);
}
