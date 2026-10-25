package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only, bounded view of blocks in a dimension. */
public interface WorldReader extends GamePort {

    CompletableFuture<WorldSnapshot> snapshot(BlockQuery query);
}
