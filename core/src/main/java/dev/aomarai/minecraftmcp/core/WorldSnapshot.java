package dev.aomarai.minecraftmcp.core;

import java.util.List;

/** A truncated, bounded view of blocks in one dimension. */
public record WorldSnapshot(String dimension, List<BlockState> blocks, boolean truncated) {

    public WorldSnapshot {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /** A block identified by its registry id, never a display name. */
    public record BlockState(int x, int y, int z, String id) {
    }
}
