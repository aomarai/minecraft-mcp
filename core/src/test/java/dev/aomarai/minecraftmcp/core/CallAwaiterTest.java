package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CallAwaiterTest {

    private static final Duration BUDGET = Duration.ofMillis(100);

    /** Reports a timeout but completes (or fails) first, as a game thread finishing at the deadline would. */
    private static final class FinishesAtTheDeadline extends CompletableFuture<String> {
        private final Throwable failure;

        FinishesAtTheDeadline(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String get(long timeout, TimeUnit unit) throws TimeoutException {
            if (failure == null) {
                complete("just made it");
            } else {
                completeExceptionally(failure);
            }
            throw new TimeoutException();
        }
    }

    @Test
    void returnsTheValueOfACompletedFuture() throws Exception {
        assertEquals("ok", CallAwaiter.await(CompletableFuture.completedFuture("ok"), BUDGET, true));
    }

    @Test
    void aFutureThatNeverCompletesTimesOutWithinTheBudgetAndIsCancelled() {
        CompletableFuture<String> never = new CompletableFuture<>();

        long start = System.nanoTime();
        TimeoutException e = assertThrows(TimeoutException.class, () -> CallAwaiter.await(never, BUDGET, false));
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertEquals("timed out after 100 ms", e.getMessage());
        assertTrue(elapsed >= 100 && elapsed < 5_000, "elapsed " + elapsed);
        assertTrue(never.isCancelled());
    }

    @Test
    void gameThreadWorkThatHadNotStartedSaysItWillNotRun() {
        java.util.List<Runnable> queue = new java.util.ArrayList<>();
        MainThread.Call<String> queued = MainThread.call(queue::add, () -> "never");

        TimeoutException write = assertThrows(TimeoutException.class, () -> CallAwaiter.await(queued, BUDGET, true));

        assertEquals("timed out after 100 ms; it had not started and will not run", write.getMessage());
        queue.forEach(Runnable::run);
        assertTrue(queued.isCancelled());
        assertFalse(queued.started(), "and it really does not run");

        MainThread.Call<String> queuedRead = MainThread.call(queue::add, () -> "never");
        assertEquals("timed out after 100 ms; it had not started and will not run",
                assertThrows(TimeoutException.class, () -> CallAwaiter.await(queuedRead, BUDGET, false)).getMessage());
    }

    @Test
    void gameThreadWorkThatWasRunningSaysItKeepsGoing() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch begun = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Executor gameThread = command -> new Thread(command, "fake-game-thread").start();
        MainThread.Call<String> slowWrite = MainThread.call(gameThread, () -> {
            begun.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        assertTrue(begun.await(5, java.util.concurrent.TimeUnit.SECONDS));

        TimeoutException write = assertThrows(TimeoutException.class, () -> CallAwaiter.await(slowWrite, BUDGET, true));
        assertEquals("timed out after 100 ms; it was already running on the game thread, so it may still have taken effect",
                write.getMessage());

        MainThread.Call<String> slowRead = MainThread.call(gameThread, () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        Thread.sleep(50);
        TimeoutException read = assertThrows(TimeoutException.class, () -> CallAwaiter.await(slowRead, BUDGET, false));
        assertEquals("timed out after 100 ms; it was already running on the game thread and will finish", read.getMessage());
        release.countDown();
    }

    @Test
    void aTimedOutWriteSaysItMayStillHaveTakenEffect() {
        CompletableFuture<String> never = new CompletableFuture<>();
        TimeoutException e = assertThrows(TimeoutException.class, () -> CallAwaiter.await(never, BUDGET, true));
        assertEquals("timed out after 100 ms; the call may still have taken effect", e.getMessage());
    }

    @Test
    void anOutcomeThatArrivesAtTheDeadlineIsKeptNotLost() throws Exception {
        assertEquals("just made it", CallAwaiter.await(new FinishesAtTheDeadline(null), BUDGET, true));
    }

    @Test
    void aFailureThatArrivesAtTheDeadlineIsReported() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                CallAwaiter.await(new FinishesAtTheDeadline(new IllegalArgumentException("bad")), BUDGET, true));
        assertEquals("bad", e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    void aFailedFutureIsReportedWithItsMessageAndCause() {
        Throwable cause = new IllegalArgumentException("boom");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CallAwaiter.await(CompletableFuture.failedFuture(cause), BUDGET, false));
        assertEquals("boom", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void aFailureWithoutAMessageIsNamedByItsClass() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CallAwaiter.await(CompletableFuture.failedFuture(new UnsupportedOperationException()), BUDGET, false));
        assertEquals("UnsupportedOperationException", e.getMessage());
    }

    @Test
    void interruptionCancelsTheFutureAndRestoresTheFlag() throws Exception {
        assertEquals("the call was interrupted before it finished; it may still have taken effect",
                interrupted(true).getMessage());
        assertEquals("the call was interrupted before it finished", interrupted(false).getMessage());
    }

    private static Throwable interrupted(boolean mayChangeWorld) throws Exception {
        CompletableFuture<String> never = new CompletableFuture<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                CallAwaiter.await(never, Duration.ofSeconds(30), mayChangeWorld);
            } catch (Exception e) {
                thrown.set(e);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Thread.sleep(50);
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive());
        assertInstanceOf(InterruptedException.class, thrown.get());
        assertTrue(never.isCancelled());
        assertEquals(Boolean.TRUE, flag.get());
        return thrown.get();
    }

    @Test
    void cancellationOfTheFutureItselfPropagates() {
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> CallAwaiter.await(cancelled, BUDGET, false));
    }

    @Test
    void executionExceptionIsNeverLeakedToCallers() {
        assertThrows(IllegalStateException.class,
                () -> CallAwaiter.await(CompletableFuture.failedFuture(new RuntimeException("x")), BUDGET, false));
        assertFalse(ExecutionException.class.isInstance(
                assertThrows(Exception.class,
                        () -> CallAwaiter.await(CompletableFuture.failedFuture(new RuntimeException("x")), BUDGET, false))));
    }
}
