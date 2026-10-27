package dev.aomarai.minecraftmcp.shim;

import java.time.Duration;

/** How long to wait before the next reconnect: the first delay, then double it each time up to a cap. */
class Backoff {

    private final Duration first;
    private final Duration cap;
    private Duration next;

    Backoff(Duration first, Duration cap) {
        this.first = first;
        this.cap = cap;
        this.next = first;
    }

    /** The delay to wait now; the one after it is twice as long, up to the cap. */
    Duration next() {
        Duration now = next;
        next = Duration.ofMillis(Math.min(now.toMillis() * 2, cap.toMillis()));
        return now;
    }

    /** A connection worked: start again from the first delay. */
    void reset() {
        next = first;
    }
}
