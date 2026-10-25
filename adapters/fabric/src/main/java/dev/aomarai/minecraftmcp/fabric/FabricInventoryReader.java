package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.InventoryContents;
import dev.aomarai.minecraftmcp.core.InventoryReader;
import dev.aomarai.minecraftmcp.core.MainThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reads inventories on the main thread. Items are reported by registry id and count only, so modded
 * items work unchanged and custom item names never reach the model. A container is only read from a
 * loaded chunk; no chunk is loaded to find it.
 */
final class FabricInventoryReader implements InventoryReader {

    private final MinecraftServer server;

    FabricInventoryReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<InventoryContents> playerInventory(String player) {
        return MainThread.call(server, () -> {
            ServerPlayer found = findPlayer(player);
            return describe(found.getGameProfile().getName(), found.getInventory());
        });
    }

    @Override
    public CompletableFuture<InventoryContents> containerInventory(String dimension, int x, int y, int z) {
        return MainThread.call(server, () -> {
            ServerLevel level = FabricDimensions.level(server, dimension);
            BlockPos pos = new BlockPos(x, y, z);
            BlockEntity entity = level.isOutsideBuildHeight(y) || !level.hasChunkAt(pos) ? null : level.getBlockEntity(pos);
            if (!(entity instanceof Container container)) {
                throw new IllegalArgumentException("no container at " + x + " " + y + " " + z + " in " + dimension
                        + " (or its chunk is not loaded)");
            }
            return describe(BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString(), container);
        });
    }

    private ServerPlayer findPlayer(String player) {
        ServerPlayer found = server.getPlayerList().getPlayerByName(player);
        if (found == null) {
            try {
                found = server.getPlayerList().getPlayer(UUID.fromString(player));
            } catch (IllegalArgumentException notAUuid) {
                found = null;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("no online player '" + player + "'");
        }
        return found;
    }

    private static InventoryContents describe(String owner, Container container) {
        List<InventoryContents.Slot> slots = new ArrayList<>();
        boolean truncated = false;
        for (int index = 0; index < container.getContainerSize(); index++) {
            ItemStack stack = container.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }
            if (slots.size() == InventoryContents.MAX_SLOTS) {
                truncated = true;
                break;
            }
            slots.add(new InventoryContents.Slot(index, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
        }
        return new InventoryContents(owner, slots, truncated);
    }
}
