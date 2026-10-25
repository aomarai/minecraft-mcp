package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ResourceIdsTest {

    @Test
    void aBareNameMeansTheMinecraftNamespace() {
        assertEquals("minecraft:stone", ResourceIds.parse("stone", "id"));
        assertEquals("minecraft:stone", ResourceIds.parse("  stone  ", "id"));
    }

    @Test
    void surroundingWhitespaceIsStrippedNotRejected() {
        assertEquals("minecraft:stone", ResourceIds.parse("minecraft:stone\n", "id"));
    }

    @Test
    void dotsAndSlashesAreLegalIdCharactersAndHarmlessBecauseIdsAreOnlyEverRegistryKeys() {
        // Never used to build a file path: it is looked up as a key, and simply finds nothing.
        assertEquals("minecraft:../etc", ResourceIds.parse("../etc", "id"));
    }

    @Test
    void aNamespacedIdIsKept() {
        assertEquals("create:cogwheel", ResourceIds.parse("create:cogwheel", "id"));
        assertEquals("minecraft:block/stone_brick", ResourceIds.parse("minecraft:block/stone_brick", "id"));
        assertEquals("my-mod.x:a_b-c.d/e", ResourceIds.parse("my-mod.x:a_b-c.d/e", "id"));
        assertEquals("a1:b2", ResourceIds.parse("a1:b2", "id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "Stone", "minecraft:Stone", "MINECRAFT:stone", "a:b:c", "has space", "minecraft:", ":stone",
            "minecraft:st one", "a b:c", "minecraft:..\\x", "minecraft:st*ne", "minecraft:@e", "\u00e9:x"})
    void malformedIdsAreRejectedWithAClearMessage(String raw) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ResourceIds.parse(raw, "registry"));

        assertTrue(e.getMessage().startsWith("registry must be a resource location like minecraft:stone"), e.getMessage());
        assertTrue(e.getMessage().endsWith("got: " + raw), e.getMessage());
    }
}
