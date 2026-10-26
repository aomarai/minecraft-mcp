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
    void anExecutorThatRefusesWorkSurfacesToTheCaller() {
        Executor refusing = command -> {
            throw new java.util.concurrent.RejectedExecutionException("server is stopping");
        };
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> MainThread.call(refusing, () -> "x"));
    }
}
