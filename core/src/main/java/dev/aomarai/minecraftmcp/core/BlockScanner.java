package dev.aomarai.minecraftmcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Walks a {@link BlockQuery} box and collects the blocks that are there.
 *
 * <p>Loader-agnostic: the adapter supplies a {@link BlockLookup} over its world. Positions are
 * visited in a fixed order (x, then y, then z, each ascending), so a truncated result is the same
 * every time. The scan never asks the world to load or generate anything: the lookup reports air,
 * positions outside the world and positions in unloaded chunks as empty, and they are skipped.</p>
 */
public final class BlockScanner {

    @FunctionalInterface
    public interface BlockLookup {
        /** The registry id of the block at the position, or empty if there is nothing to report. */
        Optional<String> blockAt(int x, int y, int z);
    }

    private BlockScanner() {
    }

    /** At most {@code query.limit()} blocks; {@code truncated} is true when more were found. */
    public static WorldSnapshot scan(BlockQuery query, BlockLookup world) {
        List<WorldSnapshot.BlockState> blocks = new ArrayList<>();
        for (int x = query.minX(); x <= query.maxX(); x++) {
            for (int y = query.minY(); y <= query.maxY(); y++) {
                for (int z = query.minZ(); z <= query.maxZ(); z++) {
                    Optional<String> id = world.blockAt(x, y, z);
                    if (id.isPresent()) {
                        if (blocks.size() == query.limit()) {
                            return new WorldSnapshot(query.dimension(), blocks, true);
                        }
                        blocks.add(new WorldSnapshot.BlockState(x, y, z, id.get()));
                    }
                }
            }
        }
        return new WorldSnapshot(query.dimension(), blocks, false);
    }
}
