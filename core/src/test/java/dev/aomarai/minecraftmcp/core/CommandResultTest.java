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
        assertThrows(UnsupportedOperationException.class, () -> result.feedback().add("c"));
    }
}
