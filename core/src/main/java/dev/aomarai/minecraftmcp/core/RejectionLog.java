package dev.aomarai.minecraftmcp.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs the requests {@link BearerAuthFilter} turns away, without letting a scanner or a client stuck in a retry loop
 * fill the game's log: the first rejection from an address in a window is logged in full, the rest are only counted,
 * and the next rejection after the window is preceded by one line with the exact count. Only the remote address is
 * ever logged. At most {@value #MAX_ADDRESSES} addresses are tracked; the least recently seen one is forgotten
 * first, and with it a pending count.
 */
final class RejectionLog {

    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int MAX_ADDRESSES = 1000;

    private static final System.Logger LOG = System.getLogger(RejectionLog.class.getName());

    /** The first rejection of a window and how many came after it. */
    private static final class Window {
        final Instant start;
        int suppressed;

        Window(Instant start) {
            this.start = start;
        }
    }

    private final Clock clock;
    private final Map<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > MAX_ADDRESSES;
        }
    };

    RejectionLog(Clock clock) {
        this.clock = clock;
    }

    synchronized void rejected(String address, String why) {
        Instant now = clock.instant();
        Window window = windows.get(address);
        if (window != null && Duration.between(window.start, now).compareTo(WINDOW) < 0) {
            window.suppressed++;
            return;
        }
        if (window != null && window.suppressed > 0) {
            LOG.log(System.Logger.Level.WARNING, "Rejected {0} more requests from {1} in the last {2} s",
                    window.suppressed, address, WINDOW.toSeconds());
        }
        windows.put(address, new Window(now));
        LOG.log(System.Logger.Level.WARNING, "Rejected request from {0}: {1}", address, why);
    }
}
