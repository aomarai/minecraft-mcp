package dev.aomarai.minecraftmcp.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Waits for an adapter future within a time budget, on an HTTP worker thread.
 *
 * <ul>
 *   <li><b>Timeout:</b> the wait ends within the budget and the future is
 *       cancelled. Adapters check {@link CompletableFuture#isCancelled()}
 *       before starting queued work, so a call that has not begun never runs.
 *       If the future finished just as the budget expired, its real outcome is
 *       returned instead of being lost.</li>
 *   <li><b>Unrecorded effects:</b> a game thread that is already mid-call
 *       cannot be stopped, so a timed-out call that can change the world
 *       reports that it "may still have taken effect". The dispatcher audits
 *       that message, so the possible side effect is never silent.</li>
 *   <li><b>Interruption:</b> the future is cancelled, the thread's
 *       interrupt flag is restored, and the exception says what happened,
 *       including whether the call may still have taken effect.</li>
 * </ul>
 */
final class CallAwaiter {

    private CallAwaiter() {
    }

    static <T> T await(CompletableFuture<T> future, Duration timeout, boolean mayChangeWorld) throws Exception {
        long millis = timeout.toMillis();
        try {
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (future.cancel(true)) {
                throw new TimeoutException("timed out after " + millis + " ms"
                        + (mayChangeWorld ? "; the call may still have taken effect" : ""));
            }
            return finished(future);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            // The bare exception has no message, which would read as just "InterruptedException" in the answer.
            throw new InterruptedException("the call was interrupted before it finished"
                    + (mayChangeWorld ? "; it may still have taken effect" : ""));
        } catch (ExecutionException e) {
            throw failure(e);
        }
    }

    /** Outcome of a future that completed while we were giving up on it. */
    private static <T> T finished(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw failure(e);
        }
    }

    private static IllegalStateException failure(ExecutionException e) {
        Throwable cause = e.getCause();
        return new IllegalStateException(Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()), cause);
    }
}
