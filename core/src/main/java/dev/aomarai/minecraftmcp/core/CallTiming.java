package dev.aomarai.minecraftmcp.core;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * How long one tool call took, in two parts: the whole call, and the share of it that held the game's main thread.
 * That second number is the one that matters for the game: a tick is 50 ms, so a call that holds the main thread
 * for longer than {@link #BUDGET} makes the server stutter, however quickly the answer reaches the client.
 *
 * <p>The dispatcher starts a timing on the worker thread that runs the call, and {@link MainThread} adds to it
 * every stretch of work it runs on the game thread for that call. The cost is a pair of {@code System.nanoTime}
 * calls per stretch and one small object per call; nothing is allocated per position or per block.</p>
 */
final class CallTiming {

    /** One tick. Main-thread work for one call longer than this is reported as an overrun. */
    static final Duration BUDGET = Duration.ofMillis(50);

    private static final ThreadLocal<CallTiming> CURRENT = new ThreadLocal<>();

    /**
     * What a finished call took. {@link #NONE} is for entries that have no timing, like a decision.
     *
     * @param mainThreadNanos time spent running on the game thread for this call
     * @param totalNanos      time from the start of the call to the end
     */
    record Result(long mainThreadNanos, long totalNanos) {

        static final Result NONE = new Result(0, 0);

        boolean overrun() {
            return mainThreadNanos > BUDGET.toNanos();
        }
    }

    private final LongSupplier nanos;
    private final long startNanos;
    private final AtomicLong mainThreadNanos = new AtomicLong();

    private CallTiming(LongSupplier nanos) {
        this.nanos = nanos;
        this.startNanos = nanos.getAsLong();
    }

    /** Starts timing the call running on this thread. Pair with {@link #end()}. */
    static CallTiming begin(LongSupplier nanos) {
        CallTiming timing = new CallTiming(nanos);
        CURRENT.set(timing);
        return timing;
    }

    static void end() {
        CURRENT.remove();
    }

    /** The timing of the call running on this thread, or null if none is (an adapter used outside a tool call). */
    static CallTiming current() {
        return CURRENT.get();
    }

    long now() {
        return nanos.getAsLong();
    }

    void addMainThread(long nanos) {
        mainThreadNanos.addAndGet(nanos);
    }

    Result finish() {
        return new Result(mainThreadNanos.get(), nanos.getAsLong() - startNanos);
    }
}
