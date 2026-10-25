package dev.aomarai.minecraftmcp.rcon;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class EntityDataTest {

    private static String message(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(IOException.class, action).getMessage();
    }

    @Test
    void aPositionIsReadAsThreeNumbers() throws IOException {
        double[] at = EntityData.position("Steve has the following entity data: [1.5d, 64.0d, -2.5d]");

        assertArrayEquals(new double[]{1.5, 64.0, -2.5}, at);
    }

    @Test
    void positionsMayBeNegativeWholeOrInScientificNotation() throws IOException {
        assertArrayEquals(new double[]{-100.0, 0.0, 12345.0},
                EntityData.position("Alex_1 has the following entity data: [-100.0d, 0.0d, 12345.0d]"));
        assertArrayEquals(new double[]{1.2E-5, -3.0E10, 4.0},
                EntityData.position("Alex has the following entity data: [1.2E-5d, -3.0E10d, 4.0d]"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() throws IOException {
        assertArrayEquals(new double[]{1, 2, 3}, EntityData.position("  Steve has the following entity data: [1.0d, 2.0d, 3.0d]\n"));
    }

    @Test
    void aDimensionIsReadWithItsNamespace() throws IOException {
        assertEquals("minecraft:overworld", EntityData.dimension("Steve has the following entity data: \"minecraft:overworld\""));
        assertEquals("mymod:deep_dark.2", EntityData.dimension("Steve has the following entity data: \"mymod:deep_dark.2\""));
        assertEquals("a:b/c", EntityData.dimension("Steve has the following entity data: \"a:b/c\""));
    }

    @Test
    void aUuidIsBuiltFromItsFourInts() throws IOException {
        // 00010000-0002-0003-0004-000000000005 is ints 1 / 0x00020003 / 0x00040000 / 5
        assertEquals("00010000-0002-0003-0004-000000000005",
                EntityData.uuid("Steve has the following entity data: [I; 65536, 131075, 262144, 5]"));
    }

    @Test
    void negativeIntsMakeTheSameUuidAsTheirUnsignedHalves() throws IOException {
        assertEquals("ffffffff-ffff-ffff-ffff-ffffffffffff", EntityData.uuid("Steve has the following entity data: [I; -1, -1, -1, -1]"));
        assertEquals("80000000-0000-0000-8000-000000000000",
                EntityData.uuid("Steve has the following entity data: [I; -2147483648, 0, -2147483648, 0]"));
        assertEquals("00000000-0000-0000-0000-000000000001", EntityData.uuid("Steve has the following entity data: [I; 0, 0, 0, 1]"));
        assertEquals("00000000-0000-0000-0000-0000ffffffff", EntityData.uuid("Steve has the following entity data: [I; 0, 0, 0, -1]"));
    }

    @Test
    void aReplyThatIsNotWhatWasAskedForIsAnErrorThatQuotesIt() {
        assertEquals("unexpected reply from the server: No entity was found",
                message(() -> EntityData.position("No entity was found")));
        assertEquals("unexpected reply from the server: Steve has the following entity data: \"minecraft:overworld\"",
                message(() -> EntityData.position("Steve has the following entity data: \"minecraft:overworld\"")));
        assertTrue(message(() -> EntityData.dimension("Steve has the following entity data: [1.0d, 2.0d, 3.0d]"))
                .startsWith("unexpected reply from the server: "));
        assertTrue(message(() -> EntityData.uuid("Steve has the following entity data: [I; 1, 2, 3]"))
                .startsWith("unexpected reply from the server: "));
    }

    @Test
    void theNameInTheReplyMustBeARealPlayerName() {
        assertThrows(IOException.class, () -> EntityData.position("Not a name! has the following entity data: [1.0d, 2.0d, 3.0d]"));
        assertThrows(IOException.class,
                () -> EntityData.position("ABCDEFGHIJKLMNOPQ has the following entity data: [1.0d, 2.0d, 3.0d]"));
        assertDoesNotThrow(() -> EntityData.position("ABCDEFGHIJKLMNOP has the following entity data: [1.0d, 2.0d, 3.0d]"));
    }

    @Test
    void anIntTooBigForAnIntIsAnError() {
        assertTrue(message(() -> EntityData.uuid("Steve has the following entity data: [I; 99999999999, 0, 0, 0]"))
                .startsWith("unexpected reply from the server: "));
    }

    @Test
    void aReplyOfExactlyEightyCharactersIsQuotedWhole() {
        String reply = "y".repeat(80);

        assertEquals("unexpected reply from the server: " + reply, message(() -> EntityData.position(reply)));
    }

    @Test
    void aLongReplyIsCutAndControlCharactersAreRemovedFromTheError() {
        String message = message(() -> EntityData.position("x".repeat(100) + "\n\u0001"));

        assertEquals("unexpected reply from the server: " + "x".repeat(80) + "...", message);
        assertEquals("unexpected reply from the server: a b",
                message(() -> EntityData.position("a\tb")));
    }
}
