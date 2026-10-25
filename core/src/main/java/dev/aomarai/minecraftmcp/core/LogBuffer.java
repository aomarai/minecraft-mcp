package dev.aomarai.minecraftmcp.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The last few log lines, kept in memory so {@code read_log} never touches the log file. An
 * adapter feeds it from the game's logger; once full, the oldest line is dropped for each new one.
 * Safe to call from any thread.
 */
public final class LogBuffer implements LogReader {

    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public LogBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
    }

    public synchronized void add(String line) {
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    @Override
    public synchronized CompletableFuture<LogLines> recentLines(LogQuery query) {
        int skip = Math.max(0, lines.size() - query.limit());
        List<String> recent = lines.stream().skip(skip).toList();
        return CompletableFuture.completedFuture(new LogLines(recent, skip > 0));
    }
}
