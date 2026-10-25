package dev.aomarai.minecraftmcp.vanilla;

import dev.aomarai.minecraftmcp.core.Inventory;
import dev.aomarai.minecraftmcp.core.InventoryReader;
import dev.aomarai.minecraftmcp.core.MainThread;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An online player's inventory, read on the main thread. Items are reported by registry id and count
 * only, so modded items work and player-controlled item names never reach the AI.
 */
public final class VanillaInventoryReader implements InventoryReader {

    private final MinecraftServer server;

    public VanillaInventoryReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Inventory> inventoryOf(String player) {
        return MainThread.call(server, () -> describe(find(player)));
    }

    private ServerPlayer find(String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) {
            throw new IllegalArgumentException("player '" + name + "' is not online");
        }
        return player;
    }

    private static Inventory describe(Player player) {
        List<Inventory.Stack> stacks = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(new Inventory.Stack(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
            }
        }
        return new Inventory(player.getName().getString(), stacks);
    }
}
