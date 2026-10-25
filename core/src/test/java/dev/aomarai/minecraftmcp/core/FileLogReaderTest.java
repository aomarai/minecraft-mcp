package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FileLogReaderTest {

    @TempDir
    Path dir;

    private Path log(String text) throws IOException {
        Path file = dir.resolve("latest.log");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aMissingLogIsEmptyNotAnError() throws IOException {
        assertEquals(List.of(), new FileLogReader(dir.resolve("none.log")).tail(10));
    }

    @Test
    void aDirectoryInsteadOfAFileIsEmptyToo() throws IOException {
        assertEquals(List.of(), new FileLogReader(dir).tail(10));
    }

    @Test
    void anEmptyLogIsEmpty() throws IOException {
        assertEquals(List.of(), new FileLogReader(log("")).tail(10));
    }

    @Test
    void fewerLinesThanAskedReturnsAllOfThemOldestFirst() throws IOException {
        assertEquals(List.of("one", "two", "three"), new FileLogReader(log("one\ntwo\nthree\n")).tail(10));
    }

    @Test
    void exactlyAsManyLinesAsAskedReturnsAllOfThem() throws IOException {
        assertEquals(List.of("one", "two", "three"), new FileLogReader(log("one\ntwo\nthree")).tail(3));
    }

    @Test
    void moreLinesThanAskedReturnsTheNewestOnes() throws IOException {
        assertEquals(List.of("three", "four"), new FileLogReader(log("one\ntwo\nthree\nfour\n")).tail(2));
        assertEquals(List.of("four"), new FileLogReader(log("one\ntwo\nthree\nfour\n")).tail(1));
    }

    @Test
    void windowsLineEndingsAreHandled() throws IOException {
        assertEquals(List.of("a", "b"), new FileLogReader(log("a\r\nb\r\n")).tail(5));
    }

    @Test
    void utf8TextIsDecoded() throws IOException {
        assertEquals(List.of("caf\u00e9 \u2603"), new FileLogReader(log("caf\u00e9 \u2603\n")).tail(1));
    }

    @Test
    void aHugeLogOnlyCostsTheLastChunkAndNeverReturnsACutOffLine() throws IOException {
        String line = "x".repeat(99);
        int count = FileLogReader.MAX_BYTES / 100 * 3;
        String text = IntStream.range(0, count).mapToObj(i -> String.format("%05d", i) + line)
                .collect(Collectors.joining("\n", "", "\n"));
        Path file = log(text);
        assertTrue(Files.size(file) > FileLogReader.MAX_BYTES * 2L);

        List<String> tail = new FileLogReader(file).tail(LogReader.MAX_LINES);

        assertEquals(LogReader.MAX_LINES, tail.size());
        assertEquals(String.format("%05d", count - 1) + line, tail.get(tail.size() - 1));
        assertEquals(String.format("%05d", count - LogReader.MAX_LINES) + line, tail.get(0));
        tail.forEach(l -> assertEquals(104, l.length(), "every line is whole"));
    }

    @Test
    void askingForMoreThanTheChunkHoldsReturnsOnlyWholeLinesFromTheChunk() throws IOException {
        String line = "y".repeat(99);
        int count = FileLogReader.MAX_BYTES / 100 * 3;
        String text = IntStream.range(0, count).mapToObj(i -> String.format("%05d", i) + line)
                .collect(Collectors.joining("\n", "", "\n"));

        List<String> tail = new FileLogReader(log(text)).tail(Integer.MAX_VALUE);

        assertTrue(tail.size() < count, "bounded by the chunk, not the file");
        assertTrue(tail.size() > 2000);
        tail.forEach(l -> assertEquals(104, l.length(), "the cut-off first line was dropped"));
        assertEquals(String.format("%05d", count - 1) + line, tail.get(tail.size() - 1));
    }

    @Test
    void aSmallFileIsReadFromItsStartWithoutDroppingTheFirstLine() throws IOException {
        assertEquals("first", new FileLogReader(log("first\nsecond\n")).tail(10).get(0));
    }
}
