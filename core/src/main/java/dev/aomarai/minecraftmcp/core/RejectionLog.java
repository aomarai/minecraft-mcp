package dev.aomarai.minecraftmcp.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Logs the requests {@link BearerAuthFilter} turns away, without letting a scanner, a client stuck in a retry loop
 * or a flood from many addresses fill the game's log. Two limits apply in every {@link #WINDOW}:
 * <ul>
 *   <li>the first rejection from an address is logged in full, the repeats from it are only counted;</li>
 *   <li>at most {@value #MAX_LINES_PER_WINDOW} lines are logged in all, whatever the addresses; the rest are only
 *       counted.</li>
 * </ul>
 * The window starts at the first rejection after the previous one ended. The next rejection from any address
 * after it is preceded by one line with the exact number of requests that were not logged, so no count is lost
 * (a count belongs to the window, not to an address, and the addresses seen are forgotten with the window, so at
 * most {@value #MAX_LINES_PER_WINDOW} are ever held). Only the remote address is ever logged.
 */
final class RejectionLog {

    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int MAX_LINES_PER_WINDOW = 20;

    private static final System.Logger LOG = System.getLogger(RejectionLog.class.getName());

    private final Clock clock;
    /** The addresses logged in this window. */
    private final Set<String> addresses = new HashSet<>();
    private Instant windowStart;
    private int logged;
    private long suppressed;

    RejectionLog(Clock clock) {
        this.clock = clock;
    }

    synchronized void rejected(String address, String why) {
        Instant now = clock.instant();
        if (windowStart == null || expired(windowStart, now)) {
            if (suppressed > 0) {
                LOG.log(System.Logger.Level.WARNING, "Rejected {0} more requests that were not logged in the last "
                        + "{1} s", suppressed, WINDOW.toSeconds());
            }
            windowStart = now;
            addresses.clear();
            logged = 0;
            suppressed = 0;
        }
        if (addresses.contains(address) || logged >= MAX_LINES_PER_WINDOW) {
            suppressed++;
            return;
        }
        addresses.add(address);
        logged++;
        LOG.log(System.Logger.Level.WARNING, "Rejected request from {0}: {1}", address, why);
    }

    private static boolean expired(Instant start, Instant now) {
        return Duration.between(start, now).compareTo(WINDOW) >= 0;
    }
}
