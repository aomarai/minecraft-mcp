package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only view of an online player's inventory. */
public interface InventoryReader extends GamePort {

    /** Fails if the player is not online. */
    CompletableFuture<Inventory> inventoryOf(String player);
}
