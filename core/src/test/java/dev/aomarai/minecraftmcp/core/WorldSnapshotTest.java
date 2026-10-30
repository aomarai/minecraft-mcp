package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldSnapshotTest {

    @Test
    void nullBlocksBecomeEmpty() {
        WorldSnapshot snapshot = new WorldSnapshot("minecraft:overworld", null, true);
        assertEquals(List.of(), snapshot.blocks());
        assertTrue(snapshot.truncated());
        assertEquals("minecraft:overworld", snapshot.dimension());
    }

    @Test
    void blocksAreDefensivelyCopied() {
        List<WorldSnapshot.BlockState> source = new ArrayList<>();
        source.add(new WorldSnapshot.BlockState(1, 2, 3, "minecraft:stone"));
        WorldSnapshot snapshot = new WorldSnapshot("d", source, false);
        source.clear();
        assertEquals(1, snapshot.blocks().size());
        WorldSnapshot.BlockState block = snapshot.blocks().get(0);
        assertEquals(1, block.x());
        assertEquals(2, block.y());
        assertEquals(3, block.z());
        assertEquals("minecraft:stone", block.id());
        assertFalse(snapshot.truncated());
        var blocks2 = snapshot.blocks();
        assertThrows(UnsupportedOperationException.class, () -> blocks2.add(block));
    }
}
