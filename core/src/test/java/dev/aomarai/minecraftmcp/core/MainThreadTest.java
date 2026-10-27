package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class MainThreadTest {

    /** A game thread that runs queued work only when the test says so. */
    private static final class QueuedThread implements Executor {
        final List<Runnable> queue = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            List<Runnable> work = new ArrayList<>(queue);
            queue.clear();
            work.forEach(Runnable::run);
        }
    }

    @Test
    void theTaskRunsOnTheExecutorNotOnTheCaller() throws Exception {
        QueuedThread game = new QueuedThread();
        Thread caller = Thread.currentThread();
        List<Thread> ranOn = new ArrayList<>();

        CompletableFuture<String> future = MainThread.call(game, () -> {
            ranOn.add(Thread.currentThread());
            return "done";
        });

        assertFalse(future.isDone(), "nothing happens until the game thread gets to it");
        assertTrue(ranOn.isEmpty());
        game.runAll();
        assertEquals("done", future.get());
        assertSame(caller, ranOn.get(0), "the test thread stands in for the game thread here");
    }

    @Test
    void aFailingTaskCompletesTheFutureExceptionally() {
        QueuedThread game = new QueuedThread();
        CompletableFuture<String> future = MainThread.call(game, () -> {
            throw new IllegalStateException("boom");
        });

        game.runAll();

        ExecutionException e = assertThrows(ExecutionException.class, future::get);
        assertEquals("boom", e.getCause().getMessage());
    }

    @Test
    void anErrorFailsTheCallAtOnceAndNeverEscapesIntoTheGameLoop() {
        QueuedThread game = new QueuedThread();
        long[] clock = {0};
        CallTiming timing = CallTiming.begin(() -> clock[0]);
        CompletableFuture<String> future;
        try {
            future = MainThread.call(game, () -> {
                clock[0] += 7;
                throw new NoSuchMethodError("Minecraft.newName");
            });
        } finally {
            CallTiming.end();
        }

        assertDoesNotThrow(game::runAll);
        assertEquals(7, timing.finish().mainThreadNanos(), "the time on the game thread is still recorded");

        assertTrue(future.isCompletedExceptionally());
        ExecutionException e = assertThrows(ExecutionException.class, future::get);
        assertEquals("NoSuchMethodError: Minecraft.newName", e.getCause().getMessage());
        assertInstanceOf(NoSuchMethodError.class, e.getCause().getCause());
    }

    @Test
    void aVirtualMachineErrorIsRethrownAfterTheFutureIsCompleted() {
        QueuedThread game = new QueuedThread();
        CompletableFuture<String> future = MainThread.call(game, () -> {
            throw new OutOfMemoryError("heap");
        });

        assertThrows(OutOfMemoryError.class, game::runAll);

        assertTrue(future.isCompletedExceptionally());
        assertEquals("OutOfMemoryError: heap",
                assertThrows(ExecutionException.class, future::get).getCause().getMessage());
    }

    @Test
    void failKeepsARuntimeExceptionAsItIs() {
        CompletableFuture<String> future = new CompletableFuture<>();
        IllegalArgumentException thrown = new IllegalArgumentException("bad");

        MainThread.fail(future, thrown);

        assertSame(thrown, assertThrows(ExecutionException.class, future::get).getCause());
    }

    @Test
    void workCancelledBeforeItStartsNeverRuns() {
        QueuedThread game = new QueuedThread();
        List<String> ran = new ArrayList<>();
        CompletableFuture<String> future = MainThread.call(game, () -> {
            ran.add("ran");
            return "x";
        });

        future.cancel(true);
        game.runAll();

        assertTrue(ran.isEmpty(), "a timed-out call must not act on the world afterwards");
        assertTrue(future.isCancelled());
    }

    @Test
    void aCallKnowsWhetherTheGameThreadHasBegunItsWork() {
        QueuedThread game = new QueuedThread();
        List<Boolean> startedWhileRunning = new ArrayList<>();
        MainThread.Call<String>[] holder = new MainThread.Call[1];
        MainThread.Call<String> call = MainThread.call(game, () -> {
            startedWhileRunning.add(holder[0].started());
            return "x";
        });
        holder[0] = call;

        assertFalse(call.started(), "queued, not begun");
        game.runAll();

        assertEquals(List.of(true), startedWhileRunning, "begun before the work itself runs");
        assertTrue(call.started());
    }

    @Test
    void workThatWasCancelledBeforeItsTurnIsNeverMarkedStarted() {
        QueuedThread game = new QueuedThread();
        MainThread.Call<String> call = MainThread.call(game, () -> "x");

        call.cancel(true);
        game.runAll();

        assertFalse(call.started());
    }

    @Test
    void aCancelThatArrivesWhileTheWorkRunsDoesNotUndoStarted() {
        QueuedThread game = new QueuedThread();
        List<String> ran = new ArrayList<>();
        MainThread.Call<String>[] holder = new MainThread.Call[1];
        holder[0] = MainThread.call(game, () -> {
            holder[0].cancel(true);
            ran.add("ran");
            return "x";
        });

        game.runAll();

        assertEquals(List.of("ran"), ran);
        assertTrue(holder[0].started(), "the work ran, so the timeout message must say it was already running");
        assertTrue(holder[0].isCancelled());
    }

    @Test
    void workRunsExactlyWhenItWasNotCancelledFirst() {
        for (boolean cancelFirst : new boolean[]{true, false}) {
            QueuedThread game = new QueuedThread();
            List<String> ran = new ArrayList<>();
            MainThread.Call<String> call = MainThread.call(game, () -> {
                ran.add("ran");
                return "x";
            });

            if (cancelFirst) {
                call.cancel(true);
            }
            game.runAll();

            assertEquals(cancelFirst, call.cancel(true), "a finished call cannot be cancelled");
            assertEquals(!cancelFirst, call.started());
            assertEquals(call.started(), !ran.isEmpty(), "started() is true if and only if the work ran");
        }
    }

    @Test
    void anExecutorThatRefusesWorkSurfacesToTheCaller() {
        Executor refusing = command -> {
            throw new java.util.concurrent.RejectedExecutionException("server is stopping");
        };
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> MainThread.call(refusing, () -> "x"));
    }
}
