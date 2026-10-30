package dev.aomarai.minecraftmcp.shim;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Waits for a condition by polling it, so a test takes as long as the condition needs and no longer. */
final class Await {

    private static final long POLL_NANOS = Duration.ofMillis(2).toNanos();

    private Await() {
    }

    /** Whether {@code condition} became true within {@code timeout}; checked once more at the deadline. */
    static boolean until(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                return condition.getAsBoolean();
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
        return true;
    }
}
