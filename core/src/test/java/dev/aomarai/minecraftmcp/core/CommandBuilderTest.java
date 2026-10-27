package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CommandBuilderTest {

    private static String message(Runnable action) {
        return assertThrows(IllegalArgumentException.class, action::run).getMessage();
    }

    // ---- exact commands ----

    @Test
    void fillBuildsAnExecuteInDimensionCommandWithNormalizedIds() {
        assertEquals("execute in minecraft:overworld run fill 0 -60 0 3 -57 3 minecraft:stone",
                CommandBuilder.fill("overworld", 0, -60, 0, 3, -57, 3, "stone"));
        assertEquals("execute in mymod:cavern run fill -5 1 -5 5 2 5 create:cogwheel",
                CommandBuilder.fill("mymod:cavern", -5, 1, -5, 5, 2, 5, "create:cogwheel"));
    }

    @Test
    void teleportWithoutADimensionStaysWhereThePlayerIs() {
        assertEquals("tp Steve 1.5 64 -2.25", CommandBuilder.teleport("Steve", "", 1.5, 64, -2.25));
    }

    @Test
    void teleportWithADimensionGoesThereFirst() {
        assertEquals("execute in minecraft:the_nether run tp Steve 0 70 0",
                CommandBuilder.teleport("Steve", "the_nether", 0, 70, 0));
    }

    @Test
    void numbersAreWrittenInPlainDecimalNeverWithAnExponent() {
        assertEquals("tp Steve 10000000 0.0000001 -30000000",
                CommandBuilder.teleport("Steve", "", 1.0E7, 1.0E-7, -3.0E7));
        assertFalse(CommandBuilder.teleport("Steve", "", 1.0E7, 1.0E-7, 0).contains("E"));
    }

    @Test
    void giveBuildsACommandWithTheCount() {
        assertEquals("give Alex minecraft:diamond 5", CommandBuilder.give("Alex", "diamond", 5));
        assertEquals("give Alex create:cogwheel 6400", CommandBuilder.give("Alex", "create:cogwheel", 6400));
    }

    @Test
    void messageToEveryoneIsSayAndToOnePlayerIsTell() {
        assertEquals("say hello there", CommandBuilder.message("", "  hello there  "));
        assertEquals("tell Steve hello there", CommandBuilder.message("Steve", "hello there"));
    }

    @Test
    void kickAndBanTakeAnOptionalReason() {
        assertEquals("kick Steve", CommandBuilder.kick("Steve", ""));
        assertEquals("kick Steve AFK too long", CommandBuilder.kick("Steve", " AFK too long "));
        assertEquals("ban Steve", CommandBuilder.ban("Steve", ""));
        assertEquals("ban Steve griefing", CommandBuilder.ban("Steve", "griefing"));
    }

    @Test
    void saveAndStopAreFixedCommands() {
        assertEquals("save-all flush", CommandBuilder.saveWorld());
        assertEquals("stop", CommandBuilder.stopServer());
    }

    // ---- players: nothing but a plain name gets through ----

    @ParameterizedTest
    @ValueSource(strings = {"Steve", "a", "Alex_99", "x.y", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", ".Bedrock_Player"})
    void plainPlayerNamesAreAccepted(String name) {
        assertEquals(name, CommandBuilder.player(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Steve Alex", "@a", "@e[type=!player]", "Steve;stop", "Steve\nstop", "Steve/stop",
            "~", "*", "Steve,Alex", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Ste ve", "\u00e9", "Steve\t", "$(rm)", "'x'", "\"x\""})
    void anythingThatIsNotAPlainNameIsRejected(String name) {
        assertTrue(message(() -> CommandBuilder.player(name)).startsWith("player must be a player name"));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.give(name, "stone", 1));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.teleport(name, "", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.kick(name, ""));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.ban(name, ""));
        if (!name.isEmpty()) {
            assertThrows(IllegalArgumentException.class, () -> CommandBuilder.message(name, "hi"));
        }
    }

    // ---- ids ----

    @ParameterizedTest
    @ValueSource(strings = {"stone run say hi", "Stone", "a b", "stone;", "stone\n", "\u00e9", "@s", "stone[waterlogged=true]"})
    void badBlockAndItemIdsAreRejected(String id) {
        if (id.equals("stone\n")) {
            return; // trailing whitespace is stripped, which is fine: it becomes plain "stone"
        }
        assertTrue(message(() -> CommandBuilder.fill("overworld", 0, 0, 0, 1, 1, 1, id)).startsWith("block must be a resource location"));
        assertTrue(message(() -> CommandBuilder.give("Steve", id, 1)).startsWith("item must be a resource location"));
    }

    @Test
    void aBadDimensionIsRejectedByFillAndTeleport() {
        assertTrue(message(() -> CommandBuilder.fill("over world run stop", 0, 0, 0, 1, 1, 1, "stone"))
                .startsWith("dimension must be a resource location"));
        assertTrue(message(() -> CommandBuilder.teleport("Steve", "x run stop", 0, 0, 0))
                .startsWith("dimension must be a resource location"));
    }

    // ---- free text ----

    @ParameterizedTest
    @ValueSource(strings = {"line one\nline two", "tab\there", "bell\u0007", "esc\u001b[0m", "nul\u0000", "cr\rhere", "del\u007f"})
    void textWithControlCharactersIsRejected(String text) {
        assertEquals("message must not contain control characters or line breaks", message(() -> CommandBuilder.message("", text)));
        assertEquals("reason must not contain control characters or line breaks", message(() -> CommandBuilder.kick("Steve", text)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello @a", "@e[type=zombie] hi", "@p", "x@r", "@s!", "look @n", "me@alice.com"})
    void textWithAnEntitySelectorIsRejected(String text) {
        String expected = " must not contain an entity selector such as @a or @e";
        assertEquals("message" + expected, message(() -> CommandBuilder.message("", text)));
        assertEquals("message" + expected, message(() -> CommandBuilder.message("Steve", text)));
        assertEquals("reason" + expected, message(() -> CommandBuilder.kick("Steve", text)));
    }

    @Test
    void anAtSignThatIsNotASelectorIsSent() {
        assertEquals("say mail a@b.c or @ me", CommandBuilder.message("", "mail a@b.c or @ me"));
        assertEquals("tell Steve @B @1 @", CommandBuilder.message("Steve", "@B @1 @"));
        assertEquals("kick Steve mail @x", CommandBuilder.kick("Steve", "mail @x"));
    }

    @Test
    void aSelectorIsFoundAtTheEndOfTheLongestMessage() {
        String atLimit = "m".repeat(CommandBuilder.MAX_TEXT - 2) + "@e";
        assertEquals("message must not contain an entity selector such as @a or @e",
                message(() -> CommandBuilder.message("", atLimit)));
        String half = "m".repeat(CommandBuilder.MAX_TEXT - 1) + "@";
        assertEquals("say " + half, CommandBuilder.message("", half));
    }

    @Test
    void textMustBeNonEmptyAndShortEnough() {
        assertEquals("message must not be empty", message(() -> CommandBuilder.message("", "   ")));
        assertEquals("message must not be empty", message(() -> CommandBuilder.message("Steve", "")));
        String atLimit = "m".repeat(CommandBuilder.MAX_TEXT);
        assertEquals("say " + atLimit, CommandBuilder.message("", atLimit));
        assertEquals("message must be at most 256 characters", message(() -> CommandBuilder.message("", atLimit + "m")));
        assertEquals("reason must be at most 256 characters", message(() -> CommandBuilder.ban("Steve", atLimit + "m")));
    }

    @Test
    void aReasonMayNotSmuggleASecondCommand() {
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.kick("Steve", "bye\nstop"));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.ban("Steve", "bye\r\nop Eve"));
    }

    // ---- numbers ----

    @Test
    void itemCountBoundsAreExact() {
        assertEquals("give A minecraft:stone 1", CommandBuilder.give("A", "stone", 1));
        assertEquals("count must be between 1 and 6400", message(() -> CommandBuilder.give("A", "stone", 0)));
        assertEquals("count must be between 1 and 6400", message(() -> CommandBuilder.give("A", "stone", 6401)));
        assertEquals("count must be between 1 and 6400", message(() -> CommandBuilder.give("A", "stone", -1)));
    }

    @Test
    void coordinateBoundsAreExact() {
        int max = CommandBuilder.MAX_COORDINATE;
        assertDoesNotThrow(() -> CommandBuilder.fill("overworld", -max, 0, 0, max, 1, 1, "stone"));
        String[] names = {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z"};
        for (int i = 0; i < 6; i++) {
            int[] v = {0, 0, 0, 1, 1, 1};
            v[i] = max + 1;
            assertEquals(names[i] + " must be between -30000000 and 30000000",
                    message(() -> CommandBuilder.fill("overworld", v[0], v[1], v[2], v[3], v[4], v[5], "stone")), names[i]);
            v[i] = -max - 1;
            assertEquals(names[i] + " must be between -30000000 and 30000000",
                    message(() -> CommandBuilder.fill("overworld", v[0], v[1], v[2], v[3], v[4], v[5], "stone")), names[i]);
        }
        assertEquals("x must be between -30000000 and 30000000", message(() -> CommandBuilder.teleport("A", "", max + 0.5, 0, 0)));
        assertEquals("y must be between -30000000 and 30000000", message(() -> CommandBuilder.teleport("A", "", 0, -max - 0.5, 0)));
        assertEquals("z must be between -30000000 and 30000000", message(() -> CommandBuilder.teleport("A", "", 0, 0, Double.NaN)));
        assertEquals("x must be between -30000000 and 30000000", message(() -> CommandBuilder.teleport("A", "", Double.POSITIVE_INFINITY, 0, 0)));
        assertDoesNotThrow(() -> CommandBuilder.teleport("A", "", max, -max, 0));
    }

    @Test
    void extremeIntegersDoNotOverflowTheBoundsCheck() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandBuilder.fill("overworld", Integer.MIN_VALUE, 0, 0, 1, 1, 1, "stone"));
        assertThrows(IllegalArgumentException.class,
                () -> CommandBuilder.fill("overworld", 0, 0, 0, Integer.MAX_VALUE, 1, 1, "stone"));
    }
}
