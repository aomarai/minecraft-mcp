package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * The non-empty slots of a player's or a container's inventory. Items are reported by registry id
 * and count only: custom item names are player-controlled text and are left out.
 *
 * @param owner     whose inventory this is: a player name, or the container's block registry id
 * @param truncated whether the inventory had more non-empty slots than {@value #MAX_SLOTS}
 */
public record InventoryContents(String owner, List<Slot> slots, boolean truncated) {

    public static final int MAX_SLOTS = 256;

    public InventoryContents {
        slots = List.copyOf(slots);
        if (slots.size() > MAX_SLOTS) {
            throw new IllegalArgumentException("slots must hold at most " + MAX_SLOTS + " entries");
        }
    }

    /** @param index the slot's position in the inventory, from 0 */
    public record Slot(int index, String item, int count) {
    }
}
