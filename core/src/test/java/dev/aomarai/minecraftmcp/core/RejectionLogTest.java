package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RejectionLogTest {

    private static final Instant START = Instant.parse("2026-10-27T10:00:00Z");

    private Instant now = START;
    private final RejectionLog log = new RejectionLog(new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    private List<String> logged(Runnable action) {
        try (LogCapture logs = new LogCapture()) {
            action.run();
            return List.copyOf(logs.lines());
        }
    }

    private void after(long seconds) {
        now = START.plusSeconds(seconds);
    }

    @Test
    void theFirstRejectionIsLoggedInFull() {
        assertEquals(List.of("Rejected request from {0}: {1} [203.0.113.9, bad token]"),
                logged(() -> log.rejected("203.0.113.9", "bad token")));
    }

    @Test
    void repeatsInsideTheWindowAreCountedNotLogged() {
        log.rejected("203.0.113.9", "bad token");

        after(59);
        assertEquals(List.of(), logged(() -> {
            log.rejected("203.0.113.9", "bad token");
            log.rejected("203.0.113.9", "bad token");
        }));
    }

    @Test
    void afterTheWindowOneSummaryWithTheExactCountPrecedesTheNewRejection() {
        log.rejected("203.0.113.9", "bad token");
        after(30);
        log.rejected("203.0.113.9", "bad token");
        log.rejected("203.0.113.9", "bad token");
        log.rejected("203.0.113.9", "bad token");

        after(60);
        assertEquals(List.of("Rejected {0} more requests from {1} in the last {2} s [3, 203.0.113.9, 60]",
                        "Rejected request from {0}: {1} [203.0.113.9, unexpected Host header]"),
                logged(() -> log.rejected("203.0.113.9", "unexpected Host header")));
    }

    @Test
    void aWindowWithNothingSuppressedHasNoSummary() {
        log.rejected("203.0.113.9", "bad token");

        after(60);
        assertEquals(List.of("Rejected request from {0}: {1} [203.0.113.9, bad token]"),
                logged(() -> log.rejected("203.0.113.9", "bad token")));
    }

    @Test
    void theNewWindowStartsAtTheRejectionThatOpenedIt() {
        log.rejected("203.0.113.9", "bad token");
        after(60);
        log.rejected("203.0.113.9", "bad token");

        after(119);
        assertEquals(List.of(), logged(() -> log.rejected("203.0.113.9", "bad token")));
        after(120);
        assertEquals(2, logged(() -> log.rejected("203.0.113.9", "bad token")).size());
    }

    @Test
    void eachAddressHasItsOwnWindow() {
        log.rejected("203.0.113.9", "bad token");

        assertEquals(List.of("Rejected request from {0}: {1} [198.51.100.7, bad token]"),
                logged(() -> log.rejected("198.51.100.7", "bad token")));
        assertEquals(List.of(), logged(() -> log.rejected("203.0.113.9", "bad token")));
    }

    @Test
    void onlyTheMostRecentAddressesAreRemembered() {
        for (int i = 0; i < RejectionLog.MAX_ADDRESSES; i++) {
            log.rejected("10.0.0." + i, "bad token");
        }
        // Exactly as many as may be tracked: none is forgotten, and seeing 10.0.0.0 again makes it the newest.
        assertEquals(List.of(), logged(() -> log.rejected("10.0.0.0", "bad token")));
        log.rejected("192.0.2.1", "bad token"); // one too many: forgets the oldest, 10.0.0.1

        assertEquals(List.of(), logged(() -> log.rejected("10.0.0.0", "bad token")));
        assertEquals(1, logged(() -> log.rejected("10.0.0.1", "bad token")).size());
    }
}
