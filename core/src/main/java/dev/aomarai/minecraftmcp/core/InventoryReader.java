package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only, bounded view of a player's inventory or of a container block in a loaded chunk. */
public interface InventoryReader extends GamePort {

    /** An online player's inventory, found by name or UUID. */
    CompletableFuture<InventoryContents> playerInventory(String player);

    /** The container block at a position; no chunk is loaded to find it. */
    CompletableFuture<InventoryContents> containerInventory(String dimension, int x, int y, int z);
}
