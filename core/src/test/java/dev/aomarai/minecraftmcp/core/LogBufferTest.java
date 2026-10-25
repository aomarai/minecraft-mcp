package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogBufferTest {

    private static LogLines read(LogBuffer buffer, int limit) {
        return buffer.recentLines(new LogQuery(limit)).join();
    }

    @Test
    void anEmptyBufferHasNoLinesAndIsNotTruncated() {
        LogLines log = read(new LogBuffer(3), 5);

        assertEquals(List.of(), log.lines());
        assertFalse(log.truncated());
    }

    @Test
    void returnsTheNewestLinesOldestFirst() {
        LogBuffer buffer = new LogBuffer(10);
        buffer.add("a");
        buffer.add("b");
        buffer.add("c");

        LogLines log = read(buffer, 2);

        assertEquals(List.of("b", "c"), log.lines());
        assertTrue(log.truncated());
    }

    @Test
    void isNotTruncatedWhenTheLimitMatchesTheLineCount() {
        LogBuffer buffer = new LogBuffer(10);
        buffer.add("a");
        buffer.add("b");

        assertFalse(read(buffer, 2).truncated());
        assertEquals(List.of("a", "b"), read(buffer, 3).lines());
        assertFalse(read(buffer, 3).truncated());
    }

    @Test
    void keepsExactlyCapacityLinesAndDropsTheOldest() {
        LogBuffer buffer = new LogBuffer(2);
        buffer.add("a");
        buffer.add("b");
        assertEquals(List.of("a", "b"), read(buffer, 10).lines());

        buffer.add("c");

        assertEquals(List.of("b", "c"), read(buffer, 10).lines());
    }

    @Test
    void capacityMustBePositive() {
        assertEquals("capacity must be at least 1", assertThrows(IllegalArgumentException.class, () -> new LogBuffer(0)).getMessage());
        assertEquals(List.of("a"), readAfterAdding(new LogBuffer(1), "a"));
    }

    private static List<String> readAfterAdding(LogBuffer buffer, String line) {
        buffer.add(line);
        return read(buffer, 1).lines();
    }
}
