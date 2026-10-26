package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Per-call timing: the pieces, and how a call run through the dispatcher reports it. */
class CallTimingTest {

    private final AtomicLong clock = new AtomicLong();

    @AfterEach
    void cleanUp() {
        CallTiming.end();
    }

    private static long millis(long value) {
        return Duration.ofMillis(value).toNanos();
    }

    @Test
    void aTimingIsOnlyCurrentBetweenBeginAndEnd() {
        assertNull(CallTiming.current());

        CallTiming timing = CallTiming.begin(clock::get);
        assertSame(timing, CallTiming.current());

        CallTiming.end();
        assertNull(CallTiming.current());
    }

    @Test
    void aTimingBelongsToTheThreadThatBeganIt() throws Exception {
        CallTiming.begin(clock::get);
        List<CallTiming> seen = new ArrayList<>();
        Thread other = new Thread(() -> seen.add(CallTiming.current()));
        other.start();
        other.join();

        assertEquals(1, seen.size());
        assertNull(seen.get(0));
    }

    @Test
    void finishReportsTheWholeCallAndTheMainThreadShareSeparately() {
        clock.set(millis(1_000));
        CallTiming timing = CallTiming.begin(clock::get);

        clock.addAndGet(millis(30));
        timing.addMainThread(millis(10));
        timing.addMainThread(millis(5));
        clock.addAndGet(millis(20));
        CallTiming.Result result = timing.finish();

        assertEquals(millis(50), result.totalNanos());
        assertEquals(millis(15), result.mainThreadNanos());
        assertFalse(result.overrun());
    }

    @Test
    void anOverrunIsMainThreadTimeOverOneTickNotTotalTime() {
        assertEquals(Duration.ofMillis(50), CallTiming.BUDGET);
        assertFalse(new CallTiming.Result(millis(50), millis(5_000)).overrun(), "exactly one tick is allowed");
        assertTrue(new CallTiming.Result(millis(50) + 1, millis(60)).overrun());
        assertFalse(new CallTiming.Result(0, millis(60_000)).overrun(), "a slow answer is not a stutter");
        assertFalse(CallTiming.Result.NONE.overrun());
    }

    // ---- MainThread adds to the timing of the call that asked for the work ----

    private static final class QueuedThread implements Executor {
        final List<Runnable> queue = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            new ArrayList<>(queue).forEach(Runnable::run);
            queue.clear();
        }
    }

    @Test
    void mainThreadWorkIsAddedToTheCallThatQueuedIt() throws Exception {
        QueuedThread game = new QueuedThread();
        CallTiming timing = CallTiming.begin(clock::get);

        CompletableFuture<String> future = MainThread.call(game, () -> {
            clock.addAndGet(millis(7));
            return "done";
        });
        CallTiming.end();
        clock.addAndGet(millis(100)); // the game thread was busy before it got to the work
        game.runAll();

        assertEquals("done", future.get());
        assertEquals(millis(7), timing.finish().mainThreadNanos(), "queueing time is not main-thread time");
    }

    @Test
    void workThatFailsStillCountsAndWorkThatNeverRunsCountsForNothing() {
        QueuedThread game = new QueuedThread();
        CallTiming timing = CallTiming.begin(clock::get);

        CompletableFuture<String> fails = MainThread.call(game, () -> {
            clock.addAndGet(millis(3));
            throw new IllegalStateException("boom");
        });
        CompletableFuture<String> cancelled = MainThread.call(game, () -> {
            clock.addAndGet(millis(1_000));
            return "never";
        });
        cancelled.cancel(true);
        game.runAll();

        assertTrue(fails.isCompletedExceptionally());
        assertEquals(millis(3), timing.finish().mainThreadNanos());
    }

    @Test
    void mainThreadWorkOutsideAToolCallIsNotTimedAndStillRuns() throws Exception {
        QueuedThread game = new QueuedThread();

        CompletableFuture<String> future = MainThread.call(game, () -> "plain");
        game.runAll();

        assertEquals("plain", future.get());
    }
}
