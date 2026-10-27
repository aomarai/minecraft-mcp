package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs adapter work on the game's main thread and hands the result back as a future.
 *
 * <p>The core calls ports from HTTP worker threads, but world state may only be touched on the
 * game thread. An adapter passes its loader's "run on the main thread" method (for example
 * {@code MinecraftServer::execute}) as the {@link Executor}.</p>
 *
 * <p>If the future is cancelled before the game thread gets to it, for instance because the call
 * timed out while the server was busy, the work is skipped: nothing runs that nobody is waiting for.
 * Work that has already started cannot be stopped.</p>
 *
 * <p>The time the work holds the game thread is added to the {@link CallTiming} of the tool call that asked for
 * it, so it is measured for every call without the adapters doing anything.</p>
 */
public final class MainThread {

    private MainThread() {
    }

    /**
     * The future of work queued for the game thread. It also knows whether the game thread has begun that work, which
     * is what decides what a timeout means: work that has not begun is skipped and never runs; work that has begun
     * runs to the end whatever the caller does.
     */
    public static final class Call<T> extends CompletableFuture<T> {

        private static final int NEW = 0;
        private static final int STARTED = 1;
        private static final int CANCELLED = 2;

        /** One decision, taken once: the game thread wins {@code STARTED} or a cancel wins {@code CANCELLED}. */
        private final AtomicInteger state = new AtomicInteger(NEW);

        /** Whether the game thread has started the work. */
        public boolean started() {
            return state.get() == STARTED;
        }

        private boolean tryStart() {
            return state.compareAndSet(NEW, STARTED);
        }

        /** A cancel that comes first stops the work from ever starting; one that comes later cannot stop it. */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            state.compareAndSet(NEW, CANCELLED);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    public static <T> Call<T> call(Executor mainThread, Supplier<T> task) {
        Call<T> future = new Call<>();
        // Taken here, on the calling thread, which is the one the dispatcher is timing; the work below runs elsewhere.
        CallTiming timing = CallTiming.current();
        mainThread.execute(() -> {
            if (!future.tryStart()) {
                return;
            }
            long start = timing == null ? 0 : timing.now();
            try {
                future.complete(task.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            } finally {
                if (timing != null) {
                    timing.addMainThread(timing.now() - start);
                }
            }
        });
        return future;
    }
}
