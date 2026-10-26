package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    public static <T> CompletableFuture<T> call(Executor mainThread, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        // Taken here, on the calling thread, which is the one the dispatcher is timing; the work below runs elsewhere.
        CallTiming timing = CallTiming.current();
        mainThread.execute(() -> {
            if (future.isCancelled()) {
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
