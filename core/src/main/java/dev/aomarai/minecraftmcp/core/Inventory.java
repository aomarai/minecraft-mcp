package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * What a player carries: item registry ids and counts only. Custom item names and lore are
 * deliberately left out because players control that text.
 */
public record Inventory(String player, List<Stack> stacks) {

    public Inventory {
        stacks = List.copyOf(stacks);
    }

    /**
     * @param slot  the game's slot index: 0-35 the main inventory (0-8 the hotbar), 36-39 armor from
     *              the boots up, 40 the off hand
     * @param item  registry id, e.g. {@code minecraft:diamond}
     */
    public record Stack(int slot, String item, int count) {
    }
}
