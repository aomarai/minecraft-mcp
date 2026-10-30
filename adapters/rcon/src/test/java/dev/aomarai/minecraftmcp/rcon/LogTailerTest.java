package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LogTailerTest {

    private record Seen(EventType type, String player, String text) {
    }

    @TempDir
    Path dir;

    private Path log;
    private final List<Seen> seen = new ArrayList<>();
    private LogTailer tailer;

    @BeforeEach
    void start() throws IOException {
        log = dir.resolve("latest.log");
        Files.writeString(log, "[10:00:00] [Server thread/INFO]: Done (1.0s)!\n");
        tailer = new LogTailer(log, (type, player, text) -> seen.add(new Seen(type, player, text)));
    }

    private void append(String text) throws IOException {
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Test
    void historyThatWasAlreadyThereIsNotReplayed() throws IOException {
        append("[10:00:01] [Server thread/INFO]: Steve joined the game\n");
        tailer.poll();
        // The first poll only finds the end of the file.
        assertEquals(List.of(), seen);

        append("[10:00:02] [Server thread/INFO]: Alex joined the game\n");
        tailer.poll();
        assertEquals(List.of(new Seen(EventType.JOIN, "Alex", "")), seen);
    }

    @Test
    void chatJoinAndLeaveBecomeEvents() throws IOException {
        tailer.poll();
        append("""
                [10:00:01] [Server thread/INFO]: Steve joined the game
                [10:00:02] [Server thread/INFO]: <Steve> hello there
                [10:00:03] [Server thread/INFO]: Steve left the game
                """);

        tailer.poll();

        assertEquals(List.of(new Seen(EventType.JOIN, "Steve", ""), new Seen(EventType.CHAT, "Steve", "hello there"),
                new Seen(EventType.LEAVE, "Steve", "")), seen);
    }

    @Test
    void thePaperAndModdedLineFormatsAreUnderstood() throws IOException {
        tailer.poll();
        append("""
                [10:00:01] [Async Chat Thread - #0/INFO]: <Steve_1> from paper
                [10:00:02] [Server thread/INFO] [net.minecraft.server.MinecraftServer/]: <Alex> from forge
                [10:00:03] [Server thread/INFO]: [Not Secure] <Sam> unsigned
                """);

        tailer.poll();

        assertEquals(List.of(new Seen(EventType.CHAT, "Steve_1", "from paper"), new Seen(EventType.CHAT, "Alex", "from forge"),
                new Seen(EventType.CHAT, "Sam", "unsigned")), seen);
    }

    @Test
    void theAddressInALoginLineIsNeverRead() throws IOException {
        tailer.poll();
        append("""
                [10:00:01] [Server thread/INFO]: Steve[/203.0.113.9:54321] logged in with entity id 5 at (0.0, 64.0, 0.0)
                [10:00:02] [Server thread/INFO]: Steve joined the game
                """);

        tailer.poll();

        assertEquals(List.of(new Seen(EventType.JOIN, "Steve", "")), seen);
        assertFalse(seen.toString().contains("203.0.113.9"));
    }

    @Test
    void textThatLooksLikeAnEventInsideChatIsOnlyChat() throws IOException {
        tailer.poll();
        append("""
                [10:00:01] [Server thread/INFO]: <Mallory> Steve joined the game
                [10:00:02] [Server thread/INFO]: [Server] Steve left the game
                [10:00:03] [Server thread/INFO]: Steve joined the game and more
                [10:00:04] [Server thread/INFO]: not a player name joined the game
                """);

        tailer.poll();

        assertEquals(List.of(new Seen(EventType.CHAT, "Mallory", "Steve joined the game")), seen);
    }

    @Test
    void namesLongerThanSixteenCharactersOrWithOddCharactersAreNotPlayers() throws IOException {
        tailer.poll();
        append("""
                [10:00:01] [Server thread/INFO]: <ABCDEFGHIJKLMNOPQ> too long
                [10:00:02] [Server thread/INFO]: <ABCDEFGHIJKLMNOP> just right
                [10:00:03] [Server thread/INFO]: <a b> spaced
                """);

        tailer.poll();

        assertEquals(List.of(new Seen(EventType.CHAT, "ABCDEFGHIJKLMNOP", "just right")), seen);
    }

    @Test
    void aLineIsOnlyReadOnceItIsComplete() throws IOException {
        tailer.poll();
        append("[10:00:01] [Server thread/INFO]: <Steve> half a ");
        tailer.poll();
        assertEquals(List.of(), seen);

        append("sentence\n");
        tailer.poll();
        assertEquals(List.of(new Seen(EventType.CHAT, "Steve", "half a sentence")), seen);
    }

    @Test
    void nothingIsReadTwice() throws IOException {
        tailer.poll();
        append("[10:00:01] [Server thread/INFO]: <Steve> once\n");

        tailer.poll();
        tailer.poll();

        assertEquals(1, seen.size());
    }

    @Test
    void windowsLineEndingsAreHandled() throws IOException {
        tailer.poll();
        append("[10:00:01] [Server thread/INFO]: <Steve> crlf\r\n");

        tailer.poll();

        assertEquals(List.of(new Seen(EventType.CHAT, "Steve", "crlf")), seen);
    }

    @Test
    void textIsReadAsUtf8() throws IOException {
        tailer.poll();
        append("[10:00:01] [Server thread/INFO]: <Steve> héllo ✓\n");

        tailer.poll();

        assertEquals("héllo ✓", seen.get(0).text());
    }

    @Test
    void aLogThatRestartedAsASmallerFileIsReadFromItsStart() throws IOException {
        append("[10:00:01] [Server thread/INFO]: <Steve> a fair amount of old chat that makes the file long\n");
        tailer.poll();

        Files.writeString(log, "[11:00:00] [Server thread/INFO]: <Alex> new\n");
        tailer.poll();

        assertEquals(List.of(new Seen(EventType.CHAT, "Alex", "new")), seen);
    }

    @Test
    void aLogThatWasReplacedByALargerFileIsReadFromItsStart() throws IOException {
        tailer.poll();
        assumeTrue(Files.readAttributes(log, java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null,
                "this file system does not identify files, so a replacement can only be seen by its size");
        Path old = dir.resolve("2026-10-25-1.log");
        Files.move(log, old);
        Files.writeString(log, """
                [11:00:00] [Server thread/INFO]: <Alex> a replacement that is longer than the log it replaced
                [11:00:01] [Server thread/INFO]: <Alex> second
                """);

        tailer.poll();

        assertEquals(List.of("a replacement that is longer than the log it replaced", "second"),
                seen.stream().map(Seen::text).toList());
    }

    @Test
    void aFileThatChangedIdentityIsReadFromItsStartEvenIfItIsNotShorter() throws IOException {
        AtomicInteger inode = new AtomicInteger(1);
        LogTailer swapped = new LogTailer(log, (type, player, text) -> seen.add(new Seen(type, player, text)),
                path -> inode.get());
        swapped.poll();

        // The new log is longer than the old one, and its first line lies inside what the old one occupied.
        inode.set(2);
        Files.writeString(log, "[1] [t]: <A> x\n[11:00:00] [Server thread/INFO]: <Alex> then a much longer second line\n");
        swapped.poll();

        assertEquals(List.of("x", "then a much longer second line"), seen.stream().map(Seen::text).toList());
    }

    @Test
    void aFileKeepingItsIdentityIsNotReadAgain() throws IOException {
        LogTailer same = new LogTailer(log, (type, player, text) -> seen.add(new Seen(type, player, text)), path -> "same");
        same.poll();
        append("[11:00:00] [Server thread/INFO]: <Alex> once\n");
        same.poll();
        same.poll();

        assertEquals(1, seen.size());
    }

    @Test
    void aFileThatCannotBeInspectedHasNoIdentity() {
        assertNull(LogTailer.fileKey(dir.resolve("never-there.log")));
    }

    @Test
    void aFileKeepsItsIdentityForAsLongAsItIsTheSameFile() throws IOException {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix"),
                "only file systems that identify files have a key to compare");
        Object before = LogTailer.fileKey(log);
        assertNotNull(before);
        append("more\n");

        assertEquals(before, LogTailer.fileKey(log));
    }

    @Test
    void aMissingFileIsWaitedFor() throws IOException {
        Files.delete(log);
        tailer.poll();
        assertEquals(List.of(), seen);

        Files.writeString(log, "[10:00:00] [Server thread/INFO]: <Steve> before\n");
        tailer.poll();
        // Appearing is like a restart: the file is new, but only what comes after the first look is news.
        assertEquals(List.of(), seen);

        append("[10:00:01] [Server thread/INFO]: <Steve> after\n");
        tailer.poll();
        assertEquals(List.of(new Seen(EventType.CHAT, "Steve", "after")), seen);
    }

    @Test
    void aFileThatDisappearsAndComesBackIsStartedOver() throws IOException {
        tailer.poll();
        Files.delete(log);
        tailer.poll();

        Files.writeString(log, "[10:00:00] [Server thread/INFO]: <Steve> one\n");
        tailer.poll();
        append("[10:00:01] [Server thread/INFO]: <Steve> two\n");
        tailer.poll();

        assertEquals(List.of("two"), seen.stream().map(Seen::text).toList());
    }

    @Test
    void aSingleHugeUnfinishedLineIsSkippedNotAwaited() throws IOException {
        tailer.poll();
        append("x".repeat((1 << 20) + 10));
        tailer.poll();

        append("\n[10:00:01] [Server thread/INFO]: <Steve> still works\n");
        tailer.poll();

        assertEquals(List.of(new Seen(EventType.CHAT, "Steve", "still works")), seen.subList(seen.size() - 1, seen.size()));
    }

    @Test
    void aBurstLargerThanOneReadIsCaughtUpOnWithoutLosingTheLineAtTheEdge() throws IOException {
        tailer.poll();
        String line = "[10:00:01] [Server thread/INFO]: <Steve> a line of ordinary chat\n";
        int lines = (3 * (1 << 20)) / line.length() + 5;
        append(line.repeat(lines) + "[10:00:02] [Server thread/INFO]: <Steve> unfinished");

        for (int i = 0; i < 6; i++) {
            tailer.poll();
        }
        append("\n");
        tailer.poll();

        assertEquals(lines + 1, seen.size());
        assertTrue(seen.subList(0, lines).stream().allMatch(e -> e.text().equals("a line of ordinary chat")),
                "no line was cut where one read ended and the next began");
        assertEquals("unfinished", seen.get(seen.size() - 1).text());
    }

    @Test
    void aShortUnfinishedLineIsNotSkipped() throws IOException {
        tailer.poll();
        append("[10:00:01] [Server thread/INFO]: <Steve> ");
        tailer.poll();

        append("finished\n");
        tailer.poll();

        assertEquals(List.of("finished"), seen.stream().map(Seen::text).toList());
    }

    @Test
    void theBackgroundPollerFollowsTheFileAndSurvivesAFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        List<Seen> got = new CopyOnWriteArrayList<>();
        try (LogTailer background = new LogTailer(log, (type, player, text) -> {
            got.add(new Seen(type, player, text));
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("a sink that fails once");
            }
            twice.countDown();
        })) {
            background.start(Duration.ofMillis(20));
            // The first poll only finds the end of the file, so keep writing until lines are followed: the first
            // one the sink refuses, and polling must carry on to deliver the next two.
            assertTrue(Await.until(() -> {
                try {
                    append("[10:00:01] [Server thread/INFO]: <Steve> again\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return twice.getCount() == 0;
            }, Duration.ofSeconds(10)), "polling carries on after the sink failed");
        }
        assertTrue(got.size() >= 3, "the refused event and the two after it");
    }

    @Test
    void aMissingLogIsOneWarningNamingTheFileAndThenRecoveryIsOne() throws IOException {
        Path missing = dir.resolve("logs").resolve("latest.log");
        LogTailer waiting = new LogTailer(missing, (type, player, text) -> seen.add(new Seen(type, player, text)));

        try (LogCapture logs = new LogCapture()) {
            waiting.pollReportingFailure();
            waiting.pollReportingFailure();
            waiting.pollReportingFailure();

            assertEquals(List.of(missing + " is missing or is not a regular file; will keep trying "), logs.lines());

            Files.createDirectories(missing.getParent());
            Files.writeString(missing, "[10:00:00] [Server thread/INFO]: Done (1.0s)!\n");
            waiting.pollReportingFailure();
            waiting.pollReportingFailure();

            assertEquals(2, logs.lines().size(), logs.lines().toString());
            assertEquals("Reading " + missing + " works again ", logs.lines().get(1));
        }
    }

    @Test
    void aFailingReadIsWarnedAboutOnceNotOnEveryPollAndRecoveryOnce() throws IOException {
        Path file = dir.resolve("latest-2.log");
        Files.writeString(file, "[10:00:00] [Server thread/INFO]: Done (1.0s)!\n");
        boolean[] failing = {false};
        LogTailer flaky = new LogTailer(file, (type, player, text) -> seen.add(new Seen(type, player, text)),
                path -> {
                    if (failing[0]) {
                        throw new IllegalStateException("no identity today");
                    }
                    return null;
                });
        flaky.pollReportingFailure();

        try (LogCapture logs = new LogCapture()) {
            failing[0] = true;
            flaky.pollReportingFailure();
            flaky.pollReportingFailure();
            flaky.pollReportingFailure();

            assertEquals(List.of("Cannot read " + file + ": java.lang.IllegalStateException: no identity today; "
                    + "will keep trying "), logs.lines());

            failing[0] = false;
            flaky.pollReportingFailure();
            flaky.pollReportingFailure();

            assertEquals(2, logs.lines().size(), logs.lines().toString());
            assertEquals("Reading " + file + " works again ", logs.lines().get(1));

            failing[0] = true;
            flaky.pollReportingFailure();
            assertEquals(3, logs.lines().size(), "a new failure after recovery is reported again");
        }
    }

    @Test
    void anIoFailureWhileReadingIsReportedAndNeverEscapes() throws IOException {
        Path file = dir.resolve("unreadable.log");
        Files.writeString(file, "a\n");
        LogTailer broken = new LogTailer(file, (type, player, text) -> { }, path -> null);
        broken.pollReportingFailure();
        Files.delete(file);
        Files.createDirectories(file); // a directory is "not a regular file" for the next poll

        try (LogCapture logs = new LogCapture()) {
            assertDoesNotThrow(broken::pollReportingFailure);

            assertEquals(1, logs.lines().size(), logs.lines().toString());
            assertTrue(logs.lines().get(0).contains(file.toString()), logs.lines().get(0));
        }
    }

    @Test
    void whatTheLogSaysIsNeverLogged() throws IOException {
        tailer.poll();
        append("[10:00:01] [Server thread/INFO]: <Steve> my password is hunter2\n");
        LogTailer refusing = new LogTailer(log, (type, player, text) -> {
            throw new IllegalStateException("refused " + type);
        });
        refusing.poll();
        append("[10:00:02] [Server thread/INFO]: <Steve> my password is hunter2\n");

        try (LogCapture logs = new LogCapture()) {
            refusing.poll();

            assertEquals(1, logs.lines().size(), logs.lines().toString());
            assertFalse(logs.lines().get(0).contains("hunter2"), logs.lines().get(0));
        }
    }

    @Test
    void aSinkThatRefusesEventsIsWarnedAboutOnceAndWhenItRecovers() throws IOException {
        boolean[] refusing = {true};
        LogTailer picky = new LogTailer(log, (type, player, text) -> {
            if (refusing[0]) {
                throw new IllegalStateException("full");
            }
        });
        picky.poll();

        try (LogCapture logs = new LogCapture()) {
            append("[10:00:01] [Server thread/INFO]: <Steve> one\n[10:00:02] [Server thread/INFO]: <Steve> two\n");
            picky.poll();

            assertEquals(List.of("The event sink refused an event: java.lang.IllegalStateException: full; will keep trying "),
                    logs.lines());

            refusing[0] = false;
            append("[10:00:03] [Server thread/INFO]: <Steve> three\n[10:00:04] [Server thread/INFO]: <Steve> four\n");
            picky.poll();

            assertEquals(2, logs.lines().size(), logs.lines().toString());
            assertEquals("The event sink takes events again ", logs.lines().get(1));
        }
    }

    @Test
    void anEventTheSinkRefusesDoesNotCostTheLinesAfterIt() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        List<String> kept = new ArrayList<>();
        LogTailer picky = new LogTailer(log, (type, player, text) -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("refused");
            }
            kept.add(text);
        });
        picky.poll();
        append("""
                [10:00:01] [Server thread/INFO]: <Steve> one
                [10:00:02] [Server thread/INFO]: <Steve> two
                [10:00:03] [Server thread/INFO]: <Steve> three
                """);

        picky.poll();

        assertEquals(List.of("two", "three"), kept);
    }

    @Test
    void theBackgroundThreadIsADaemon() throws Exception {
        try (LogTailer background = new LogTailer(log, (type, player, text) -> { })) {
            background.start(Duration.ofMillis(20));

            assertTrue(Await.until(() -> Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.getName().equals("minecraft-mcp-log-tail")), Duration.ofSeconds(10)));
            Thread poller = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("minecraft-mcp-log-tail")).findFirst().orElseThrow();
            assertTrue(poller.isDaemon());
        }
    }
}
