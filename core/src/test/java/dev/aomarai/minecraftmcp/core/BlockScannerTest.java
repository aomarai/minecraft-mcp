package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlockScannerTest {

    private static BlockQuery query(int maxX, int maxY, int maxZ, int limit) {
        return new BlockQuery("minecraft:overworld", 0, 0, 0, maxX, maxY, maxZ, limit);
    }

    private static List<String> coordinates(WorldSnapshot snapshot) {
        return snapshot.blocks().stream().map(b -> b.x() + "," + b.y() + "," + b.z()).toList();
    }

    @Test
    void everyPositionIsAskedExactlyOnceInAscendingXYZOrder() {
        List<String> asked = new ArrayList<>();

        BlockScanner.scan(query(1, 1, 1, 8), (x, y, z) -> {
            asked.add(x + "," + y + "," + z);
            return Optional.empty();
        });

        assertEquals(List.of("0,0,0", "0,0,1", "0,1,0", "0,1,1", "1,0,0", "1,0,1", "1,1,0", "1,1,1"), asked);
    }

    @Test
    void anEmptyWorldGivesAnEmptyUntruncatedSnapshot() {
        WorldSnapshot snapshot = BlockScanner.scan(query(2, 2, 2, 10), (x, y, z) -> Optional.empty());

        assertEquals("minecraft:overworld", snapshot.dimension());
        assertTrue(snapshot.blocks().isEmpty());
        assertFalse(snapshot.truncated());
    }

    @Test
    void blocksAreReturnedWithTheirRegistryIdsAndPositions() {
        WorldSnapshot snapshot = BlockScanner.scan(query(1, 0, 0, 10), (x, y, z) ->
                Optional.of(x == 0 ? "minecraft:stone" : "create:cogwheel"));

        assertEquals(List.of(
                new WorldSnapshot.BlockState(0, 0, 0, "minecraft:stone"),
                new WorldSnapshot.BlockState(1, 0, 0, "create:cogwheel")), snapshot.blocks());
        assertFalse(snapshot.truncated());
    }

    @Test
    void positionsWithNothingToReportAreSkipped() {
        WorldSnapshot snapshot = BlockScanner.scan(query(3, 0, 0, 10), (x, y, z) ->
                x % 2 == 0 ? Optional.of("minecraft:dirt") : Optional.empty());

        assertEquals(List.of("0,0,0", "2,0,0"), coordinates(snapshot));
        assertFalse(snapshot.truncated());
    }

    @Test
    void exactlyTheLimitIsNotTruncated() {
        WorldSnapshot snapshot = BlockScanner.scan(query(2, 0, 0, 3), (x, y, z) -> Optional.of("minecraft:stone"));

        assertEquals(3, snapshot.blocks().size());
        assertFalse(snapshot.truncated(), "three blocks fit in a limit of three");
    }

    @Test
    void oneMoreThanTheLimitIsTruncatedAndKeepsTheFirstOnes() {
        WorldSnapshot snapshot = BlockScanner.scan(query(3, 0, 0, 3), (x, y, z) -> Optional.of("minecraft:stone"));

        assertEquals(List.of("0,0,0", "1,0,0", "2,0,0"), coordinates(snapshot));
        assertTrue(snapshot.truncated());
    }

    @Test
    void scanningStopsOnceTheLimitIsExceeded() {
        int[] asked = {0};

        BlockScanner.scan(query(99, 99, 99, 1), (x, y, z) -> {
            asked[0]++;
            return Optional.of("minecraft:stone");
        });

        assertEquals(2, asked[0], "one block kept, then the second one proves truncation");
    }

    @Test
    void aSinglePositionBoxWorks() {
        WorldSnapshot snapshot = BlockScanner.scan(
                new BlockQuery("minecraft:the_nether", 5, 6, 7, 5, 6, 7, 1), (x, y, z) -> Optional.of("minecraft:netherrack"));

        assertEquals("minecraft:the_nether", snapshot.dimension());
        assertEquals(List.of(new WorldSnapshot.BlockState(5, 6, 7, "minecraft:netherrack")), snapshot.blocks());
        assertFalse(snapshot.truncated());
    }

    @Test
    void negativeCoordinatesAreWalkedUpwards() {
        List<Integer> xs = new ArrayList<>();

        BlockScanner.scan(new BlockQuery("d", -2, 0, 0, 0, 0, 0, 5), (x, y, z) -> {
            xs.add(x);
            return Optional.empty();
        });

        assertEquals(List.of(-2, -1, 0), xs);
    }
}
