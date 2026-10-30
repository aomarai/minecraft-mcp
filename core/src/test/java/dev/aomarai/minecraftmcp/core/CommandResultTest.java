package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandResultTest {

    @Test
    void okCarriesOutputWithoutFeedback() {
        CommandResult result = CommandResult.ok("done");
        assertTrue(result.success());
        assertEquals("done", result.output());
        assertTrue(result.feedback().isEmpty());
    }

    @Test
    void errorCarriesMessageAsFeedback() {
        CommandResult result = CommandResult.error("boom");
        assertFalse(result.success());
        assertEquals("", result.output());
        assertEquals(List.of("boom"), result.feedback());
    }

    @Test
    void errorWithNullMessageUsesDefault() {
        assertEquals(List.of("unknown error"), CommandResult.error(null).feedback());
    }

    @Test
    void nullsAreNormalised() {
        CommandResult result = new CommandResult(true, null, null);
        assertEquals("", result.output());
        assertEquals(List.of(), result.feedback());
    }

    @Test
    void feedbackIsDefensivelyCopied() {
        List<String> source = new ArrayList<>(List.of("a"));
        CommandResult result = new CommandResult(true, "x", source);
        source.add("b");
        assertEquals(List.of("a"), result.feedback());
        var feedback2 = result.feedback();
        assertThrows(UnsupportedOperationException.class, () -> feedback2.add("c"));
    }

    @Test
    void outputIsCutAtTheCapWithANoteSayingSo() {
        String note = "\u2026 (truncated at 16384 characters)";
        String belowCap = "o".repeat(CommandResult.MAX_OUTPUT - 1);
        String atCap = "o".repeat(CommandResult.MAX_OUTPUT);

        assertEquals(belowCap, CommandResult.ok(belowCap).output());
        assertEquals(atCap, CommandResult.ok(atCap).output());
        assertEquals(atCap + note, CommandResult.ok(atCap + "o").output());
        assertEquals(atCap + note, CommandResult.ok(atCap + "o".repeat(100_000)).output());
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        String note = "\u2026 (truncated at 16384 characters)";
        String text = "o".repeat(CommandResult.MAX_OUTPUT - 1) + "\uD83D\uDE00";

        assertEquals("o".repeat(CommandResult.MAX_OUTPUT - 1) + note, CommandResult.ok(text).output());
    }
}
