package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.EventType;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows the game server's {@code logs/latest.log} and turns what players did into events: chat, joins
 * and leaves. A server without a mod cannot tell us these any other way.
 *
 * <p>It starts at the end of the file, so old history is not replayed, and reads only whole lines. When
 * the game restarts, the log is replaced by a new, smaller file; the tailer notices that and reads the new
 * one from the start. It records nothing but the player's name and what they said: the address in a login
 * line is never read, let alone kept.</p>
 *
 * <p>Whatever players write is untrusted. The core's {@code EventBuffer} cleans and shortens it and marks
 * it so, and nothing here acts on it.</p>
 */
public final class LogTailer implements AutoCloseable {

    /** Where events go; in production {@code EventBuffer::publish}. */
    @FunctionalInterface
    public interface Sink {
        void accept(EventType type, String player, String text);
    }

    /** Everything before the message: bracketed time, thread and, on modded servers, logger, then a colon. */
    private static final String PREFIX = "^(?:\\[[^\\]]*\\]\\s*)+:\\s";
    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    /** Chat is {@code <name> text}; presence is {@code name joined the game} or {@code name left the game}. */
    private static final Pattern LINE = Pattern.compile(PREFIX + "(?:(?:\\[Not Secure\\]\\s)?<" + NAME + ">\\s(.*)|"
            + NAME + " (joined|left) the game)$");

    /** Cap on what one poll reads, so a log that suddenly grows by gigabytes cannot stall or exhaust memory. */
    private static final int MAX_READ = 1 << 20;

    private final Path file;
    private final Sink events;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "minecraft-mcp-log-tail");
        thread.setDaemon(true);
        return thread;
    });
    private final Function<Path, Object> identityOf;
    private long position;
    private boolean positioned;
    private Object identity;

    public LogTailer(Path file, Sink events) {
        this(file, events, LogTailer::fileKey);
    }

    /** @param identityOf tells one file from another that took its place under the same name */
    LogTailer(Path file, Sink events, Function<Path, Object> identityOf) {
        this.file = file;
        this.events = events;
        this.identityOf = identityOf;
    }

    /**
     * The operating system's identity of the file (an inode on Linux), which changes when a new log is created
     * under the old name. Some file systems have none; then only a log that came back shorter is noticed.
     */
    static Object fileKey(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    /** Polls the file every {@code interval} until closed. */
    public void start(Duration interval) {
        poller.scheduleWithFixedDelay(() -> quietly(this::poll), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @FunctionalInterface
    interface Step {
        void run() throws IOException;
    }

    /**
     * Runs one step and shrugs off its failure. The file may be mid-rotation, and one event that the sink
     * cannot take must not cost the lines after it; a tailer that stopped on one bad read would never
     * recover, because a scheduled task that throws is never run again.
     */
    static void quietly(Step step) {
        try {
            step.run();
        } catch (IOException | RuntimeException e) {
            // Try again next time.
        }
    }

    /** Reads whatever was added since the last call. Not thread-safe: called by the poller, or by a test. */
    void poll() throws IOException {
        if (!Files.isRegularFile(file)) {
            positioned = false;
            return;
        }
        Object current = identityOf.apply(file);
        long size = Files.size(file);
        if (!positioned) {
            // First sight of this file: only what happens from now on.
            position = size;
            identity = current;
            positioned = true;
            return;
        }
        if (size < position || !Objects.equals(identity, current)) {
            // Replaced or truncated: it is a new log, so read it from its start.
            position = 0;
            identity = current;
        }
        read(Math.min(size - position, MAX_READ));
    }

    private void read(long count) throws IOException {
        byte[] chunk = new byte[(int) count];
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            in.seek(position);
            in.readFully(chunk);
        }
        // Only whole lines are consumed. One byte is one character in ISO-8859-1, so the index found here is a byte
        // offset, whatever multi-byte text the line holds.
        int consumed = new String(chunk, StandardCharsets.ISO_8859_1).lastIndexOf('\n') + 1;
        if (consumed == 0 && chunk.length == MAX_READ) {
            // A "line" this long is not a log line; skip it rather than wait for an end that may never come.
            consumed = chunk.length;
        }
        position += consumed;
        for (String line : new String(chunk, 0, consumed, StandardCharsets.UTF_8).split("\n", -1)) {
            publish(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
    }

    private void publish(String line) {
        Matcher matcher = LINE.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        if (matcher.group(1) != null) {
            deliver(EventType.CHAT, matcher.group(1), matcher.group(2));
        } else {
            deliver(matcher.group(4).equals("joined") ? EventType.JOIN : EventType.LEAVE, matcher.group(3), "");
        }
    }

    /** One event the sink cannot take must not cost the lines after it. */
    private void deliver(EventType type, String player, String text) {
        quietly(() -> events.accept(type, player, text));
    }

    @Override
    public void close() {
        poller.shutdownNow();
    }
}
