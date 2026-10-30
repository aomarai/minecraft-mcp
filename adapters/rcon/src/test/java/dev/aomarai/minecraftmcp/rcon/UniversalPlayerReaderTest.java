package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.CommandResult;
import dev.aomarai.minecraftmcp.core.PlayerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UniversalPlayerReaderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path dir;

    private final List<String> asked = new CopyOnWriteArrayList<>();
    /** What the game answers, by command; a command missing from here is a failure ("no entity found"). */
    private final Map<String, String> answers = new java.util.HashMap<>();

    private void playerIs(String name, String position, String dimension, String uuidInts) {
        answers.put("data get entity " + name + " Pos", name + " has the following entity data: " + position);
        answers.put("data get entity " + name + " Dimension", name + " has the following entity data: " + dimension);
        answers.put("data get entity " + name + " UUID", name + " has the following entity data: " + uuidInts);
    }

    private UniversalPlayerReader reader(UniversalPlayerReader.OnlinePlayers online) {
        return new UniversalPlayerReader(online, (command, timeout) -> {
            asked.add(command);
            String answer = answers.get(command);
            return CompletableFuture.completedFuture(answer == null ? CommandResult.error("No entity was found")
                    : CommandResult.ok(answer));
        }, dir.resolve("ops.json"), TIMEOUT, Runnable::run);
    }

    private List<PlayerInfo> list(UniversalPlayerReader reader) throws Exception {
        return reader.listPlayers().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aPlayerIsDescribedFromWhatTheServerSays() throws Exception {
        playerIs("Steve", "[1.5d, 64.0d, -2.5d]", "\"minecraft:the_nether\"", "[I; 0, 1, 0, 2]");

        List<PlayerInfo> players = list(reader(() -> List.of("Steve")));

        assertEquals(List.of(new PlayerInfo("Steve", "00000000-0000-0001-0000-000000000002", "minecraft:the_nether",
                1.5, 64.0, -2.5, false)), players);
        assertEquals(List.of("data get entity Steve Pos", "data get entity Steve Dimension", "data get entity Steve UUID"), asked);
    }

    @Test
    void operatorsAreMarkedFromTheServersOwnListIgnoringCase() throws Exception {
        Files.writeString(dir.resolve("ops.json"), "[{\"uuid\":\"x\",\"name\":\"STEVE\",\"level\":4}]");
        playerIs("Steve", "[0.0d, 0.0d, 0.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 1]");
        playerIs("Alex", "[0.0d, 0.0d, 0.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");

        List<PlayerInfo> players = list(reader(() -> List.of("Steve", "Alex")));

        assertEquals(List.of(true, false), players.stream().map(PlayerInfo::op).toList());
    }

    @Test
    void aServerWithNoPlayersHasAnEmptyList() throws Exception {
        assertEquals(List.of(), list(reader(List::of)));
        assertEquals(List.of(), asked);
    }

    @Test
    void aPlayerWhoLeftBeforeTheyWereAskedAboutIsSkipped() throws Exception {
        playerIs("Alex", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");

        List<PlayerInfo> players = list(reader(() -> List.of("Gone", "Alex")));

        assertEquals(List.of("Alex"), players.stream().map(PlayerInfo::name).toList());
    }

    @Test
    void aPlayerWhoseOnlyOneAnswerFailedIsStillSkipped() throws Exception {
        playerIs("Alex", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");
        answers.remove("data get entity Alex UUID");
        assertEquals(List.of(), list(reader(() -> List.of("Alex"))));

        playerIs("Alex", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");
        answers.remove("data get entity Alex Dimension");
        assertEquals(List.of(), list(reader(() -> List.of("Alex"))));

        playerIs("Alex", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");
        answers.remove("data get entity Alex Pos");
        assertEquals(List.of(), list(reader(() -> List.of("Alex"))));
    }

    @Test
    void aNameThatCouldBeACommandInjectionIsNeverSentToTheGame() throws Exception {
        playerIs("Alex", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");

        List<PlayerInfo> players = list(reader(() -> List.of("Steve; op Mallory", "x y", "", "ABCDEFGHIJKLMNOPQ", "Alex")));

        assertEquals(List.of("Alex"), players.stream().map(PlayerInfo::name).toList());
        assertTrue(asked.stream().noneMatch(c -> c.contains("Mallory") || c.contains(";")), asked.toString());
    }

    @Test
    void aNameOfExactlySixteenCharactersIsAllowed() throws Exception {
        playerIs("ABCDEFGHIJKLMNOP", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");

        assertEquals(1, list(reader(() -> List.of("ABCDEFGHIJKLMNOP"))).size());
    }

    @Test
    void atMostOneHundredPlayersAreListed() throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            String name = "P" + i;
            names.add(name);
            playerIs(name, "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, " + i + "]");
        }

        List<PlayerInfo> players = list(reader(() -> names));

        assertEquals(100, players.size());
        assertEquals("P99", players.get(99).name());
        assertEquals(300, asked.size(), "nobody beyond the cap is asked about");
    }

    @Test
    void exactlyOneHundredPlayersAreAllListed() throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            names.add("P" + i);
            playerIs("P" + i, "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, " + i + "]");
        }

        assertEquals(100, list(reader(() -> names)).size());
    }

    @Test
    void whenQueryFailsTheListFailsWithItsMessage() {
        UniversalPlayerReader failing = reader(() -> {
            throw new IOException("the server did not answer the query");
        });

        ExecutionException failure = assertThrows(ExecutionException.class, () -> failing.listPlayers().get(10, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof java.io.UncheckedIOException);
        assertEquals("the server did not answer the query", failure.getCause().getCause().getMessage());
    }

    @Test
    void anUnreadableOperatorListFailsTheList() throws Exception {
        Files.writeString(dir.resolve("ops.json"), "not json");

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> reader(() -> List.of("Steve")).listPlayers().get(10, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof java.io.UncheckedIOException);
    }

    @Test
    void aReplyThatIsNotWhatWasAskedForFailsTheList() {
        answers.put("data get entity Steve Pos", "Steve has the following entity data: nonsense");
        answers.put("data get entity Steve Dimension", "Steve has the following entity data: \"minecraft:overworld\"");
        answers.put("data get entity Steve UUID", "Steve has the following entity data: [I; 0, 0, 0, 1]");

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> reader(() -> List.of("Steve")).listPlayers().get(10, TimeUnit.SECONDS));

        assertTrue(failure.getCause().getCause().getMessage().startsWith("unexpected reply from the server"));
    }

    @Test
    void aServerThatDoesNotAnswerACommandFailsTheListWithoutHangingForever() {
        UniversalPlayerReader silent = new UniversalPlayerReader(() -> List.of("Steve"),
                (command, timeout) -> new CompletableFuture<>(), dir.resolve("ops.json"), Duration.ofMillis(100), Runnable::run);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> silent.listPlayers().get(10, TimeUnit.SECONDS));

        assertEquals("the server did not answer 'data get entity Steve Pos'", failure.getCause().getCause().getMessage());
    }

    @Test
    void aSlowAnswerIsWaitedForAShortWhileBeyondTheTimeout() throws Exception {
        playerIs("Steve", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 1]");
        UniversalPlayerReader slow = new UniversalPlayerReader(() -> List.of("Steve"), (command, timeout) ->
                CompletableFuture.supplyAsync(() -> CommandResult.ok(answers.get(command)),
                        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)),
                dir.resolve("ops.json"), Duration.ofMillis(100), Runnable::run, () -> 0L);

        assertEquals(1, list(slow).size());
    }

    /** A clock that says 0 when the listing starts and {@code laterNanos} for every look after that. */
    private static java.util.function.LongSupplier clockThatJumpsTo(long laterNanos) {
        long[] now = {0};
        return () -> {
            long value = now[0];
            now[0] = laterNanos;
            return value;
        };
    }

    private UniversalPlayerReader readerWithClock(java.util.function.LongSupplier clock) {
        return new UniversalPlayerReader(() -> List.of("Steve"), (command, timeout) -> {
            asked.add(command);
            return CompletableFuture.completedFuture(CommandResult.ok(answers.get(command)));
        }, dir.resolve("ops.json"), Duration.ofMillis(100), Runnable::run, clock);
    }

    @Test
    void theListingIsGivenTheCallsTimeInTotalAndAskedNothingOnceItIsSpent() {
        playerIs("Steve", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 1]");

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> readerWithClock(clockThatJumpsTo(100_000_000L)).listPlayers().get(10, TimeUnit.SECONDS));

        assertEquals("stopped listing the players: the call was cancelled or took longer than 100 ms", failure.getCause().getCause().getMessage());
        assertEquals(List.of(), asked);
    }

    @Test
    void aNanosecondOfTheListingsTimeIsStillEnoughToAsk() throws Exception {
        playerIs("Steve", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 1]");

        assertEquals(1, list(readerWithClock(clockThatJumpsTo(99_999_999L))).size());
    }

    @Test
    void eachCommandGetsOnlyWhatIsLeftOfTheListingsTime() throws Exception {
        playerIs("Steve", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 1]");
        List<Duration> given = new CopyOnWriteArrayList<>();
        UniversalPlayerReader reader = new UniversalPlayerReader(() -> List.of("Steve"), (command, timeout) -> {
            given.add(timeout);
            return CompletableFuture.completedFuture(CommandResult.ok(answers.get(command)));
        }, dir.resolve("ops.json"), Duration.ofMillis(100), Runnable::run, clockThatJumpsTo(40_000_000L));

        list(reader);

        assertEquals(List.of(Duration.ofMillis(60), Duration.ofMillis(60), Duration.ofMillis(60)), given);
    }

    @Test
    void onceTheCallIsCancelledNoFurtherCommandIsSentAndTheNextCommandIsNotKeptWaiting() throws Exception {
        playerIs("Steve", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 1]");
        playerIs("Alex", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<CommandResult> first = new CompletableFuture<>();
            java.util.concurrent.CountDownLatch askedFirst = new java.util.concurrent.CountDownLatch(1);
            UniversalPlayerReader reader = new UniversalPlayerReader(() -> List.of("Steve", "Alex"), (command, timeout) -> {
                asked.add(command);
                askedFirst.countDown();
                return asked.size() == 1 ? first : CompletableFuture.completedFuture(CommandResult.ok(answers.get(command)));
            }, dir.resolve("ops.json"), TIMEOUT, worker);

            CompletableFuture<List<PlayerInfo>> listing = reader.listPlayers();
            assertTrue(askedFirst.await(10, TimeUnit.SECONDS));
            listing.cancel(true);
            first.complete(CommandResult.ok(answers.get("data get entity Steve Pos")));
            worker.submit(() -> { }).get(10, TimeUnit.SECONDS);

            assertEquals(List.of("data get entity Steve Pos"), asked, "nothing was asked after the cancel");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void aCancelBetweenPlayersStopsBeforeTheNextPlayer() throws Exception {
        playerIs("Steve", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 1]");
        playerIs("Alex", "[1.0d, 2.0d, 3.0d]", "\"minecraft:overworld\"", "[I; 0, 0, 0, 2]");
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<List<PlayerInfo>>> listing =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch assigned = new java.util.concurrent.CountDownLatch(1);
            UniversalPlayerReader reader = new UniversalPlayerReader(() -> List.of("Steve", "Alex"), (command, timeout) -> {
                try {
                    assigned.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                asked.add(command);
                if (command.endsWith("UUID")) {
                    listing.get().cancel(true);
                }
                return CompletableFuture.completedFuture(CommandResult.ok(answers.get(command)));
            }, dir.resolve("ops.json"), TIMEOUT, worker);

            listing.set(reader.listPlayers());
            assigned.countDown();
            worker.submit(() -> { }).get(10, TimeUnit.SECONDS);

            assertEquals(List.of("data get entity Steve Pos", "data get entity Steve Dimension", "data get entity Steve UUID"), asked);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void aCommandThatFailsWithAnExceptionFailsTheList() {
        UniversalPlayerReader broken = new UniversalPlayerReader(() -> List.of("Steve"),
                (command, timeout) -> CompletableFuture.failedFuture(new IllegalStateException("boom")),
                dir.resolve("ops.json"), TIMEOUT, Runnable::run);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> broken.listPlayers().get(10, TimeUnit.SECONDS));

        assertEquals("the server did not answer 'data get entity Steve Pos'", failure.getCause().getCause().getMessage());
    }

    @Test
    void anInterruptedWaitStopsTheListAndKeepsTheInterrupt() {
        UniversalPlayerReader waiting = new UniversalPlayerReader(() -> List.of("Steve"),
                (command, timeout) -> new CompletableFuture<>(), dir.resolve("ops.json"), Duration.ofSeconds(30), Runnable::run);
        Thread.currentThread().interrupt();

        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.listPlayers().get(10, TimeUnit.SECONDS));
            assertEquals("interrupted while asking the server", failure.getCause().getCause().getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
