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
 * <p>Failures do not stop it: a tailer that stopped on one bad read would never recover, because a scheduled task
 * that throws is never run again. But they are not silent either. The first failure to read the log (a file that
 * is missing or not readable, a wrong {@code serverDirectory}) is logged as a warning, once, and so is the recovery;
 * the same goes for an event sink that refuses events. What a line says is never logged.</p>
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

    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    /**
     * What follows the prefix: chat is {@code <name> text}; presence is {@code name joined the game} or
     * {@code name left the game}. The prefix itself is cut off by {@link #prefixEnd}, not matched here.
     */
    private static final Pattern MESSAGE = Pattern.compile("(?:(?:\\[Not Secure\\]\\s)?<" + NAME + ">\\s(.*)|"
            + NAME + " (joined|left) the game)");

    /** Cap on what one poll reads, so a log that suddenly grows by gigabytes cannot stall or exhaust memory. */
    private static final int MAX_READ = 1 << 20;

    private static final System.Logger LOG = System.getLogger(LogTailer.class.getName());

    /** A condition that is reported when it starts and again when it ends, not on every poll in between. */
    private static final class Problem {
        private boolean active;

        void raise(String message) {
            if (!active) {
                active = true;
                LOG.log(System.Logger.Level.WARNING, message + "; will keep trying");
            }
        }

        void clear(String message) {
            if (active) {
                active = false;
                LOG.log(System.Logger.Level.WARNING, message);
            }
        }
    }

    private final Path file;
    private final Sink events;
    private final ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(DaemonThreads.named("minecraft-mcp-log-tail"));
    private final Function<Path, Object> identityOf;
    private final Problem reading = new Problem();
    private final Problem sink = new Problem();
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
        poller.scheduleWithFixedDelay(this::pollReportingFailure, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One poll that never throws: the file may be mid-rotation, so the next poll tries again. */
    void pollReportingFailure() {
        try {
            poll();
        } catch (IOException | RuntimeException e) {
            reading.raise("Cannot read " + file + ": " + e);
        }
    }

    /** Reads whatever was added since the last call. Not thread-safe: called by the poller, or by a test. */
    void poll() throws IOException {
        if (!Files.isRegularFile(file)) {
            positioned = false;
            reading.raise(file + " is missing or is not a regular file");
            return;
        }
        follow();
        reading.clear("Reading " + file + " works again");
    }

    private void follow() throws IOException {
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

    /**
     * Where the message starts: after the bracketed time, thread and, on modded servers, logger, then a colon and
     * a whitespace character, or -1 if the line has no such prefix. Done by hand because a regex repetition around
     * the brackets recurses once per bracket, and a long hostile line turns that into a StackOverflowError that
     * would end the tailer.
     */
    static int prefixEnd(String line) {
        int length = line.length();
        int i = 0;
        while (i < length && line.charAt(i) == '[') {
            int close = line.indexOf(']', i + 1);
            if (close < 0) {
                return -1;
            }
            i = close + 1;
            while (i < length && isSpace(line.charAt(i))) {
                i++;
            }
        }
        if (i == 0 || i >= length - 1 || line.charAt(i) != ':' || !isSpace(line.charAt(i + 1))) {
            return -1;
        }
        return i + 2;
    }

    /** The characters of the regex class {@code \s}. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private void publish(String line) {
        int start = prefixEnd(line);
        if (start < 0) {
            return;
        }
        Matcher matcher = MESSAGE.matcher(line).region(start, line.length());
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
        try {
            events.accept(type, player, text);
            sink.clear("The event sink takes events again");
        } catch (RuntimeException e) {
            sink.raise("The event sink refused an event: " + e);
        }
    }

    @Override
    public void close() {
        poller.shutdownNow();
    }
}
