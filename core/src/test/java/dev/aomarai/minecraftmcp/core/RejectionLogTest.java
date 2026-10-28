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
        assertEquals(List.of("Rejected {0} more requests that were not logged in the last {1} s [3, 60]",
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
    void anAddressIsLoggedAgainInTheNextWindow() {
        log.rejected("203.0.113.9", "bad token");
        after(60);
        assertEquals(List.of(line("203.0.113.9")), logged(() -> log.rejected("203.0.113.9", "bad token")));
    }

    private static final String SUMMARY = "Rejected {0} more requests that were not logged in the last {1} s";

    private static String line(String address) {
        return "Rejected request from {0}: {1} [" + address + ", bad token]";
    }

    @Test
    void aFloodFromManyAddressesIsCappedAtTheBudgetPerWindow() {
        List<String> lines = logged(() -> {
            for (int i = 0; i <= RejectionLog.MAX_LINES_PER_WINDOW; i++) {
                log.rejected("2001:db8::" + i, "bad token");
            }
        });

        assertEquals(RejectionLog.MAX_LINES_PER_WINDOW, lines.size(), "M + 1 addresses give M lines");
        assertEquals(line("2001:db8::0"), lines.get(0));
        assertEquals(line("2001:db8::" + (RejectionLog.MAX_LINES_PER_WINDOW - 1)),
                lines.get(RejectionLog.MAX_LINES_PER_WINDOW - 1));
    }

    @Test
    void theBudgetIsExactlyMLines() {
        List<String> lines = logged(() -> {
            for (int i = 0; i < RejectionLog.MAX_LINES_PER_WINDOW - 1; i++) {
                log.rejected("10.1.0." + i, "bad token");
            }
        });
        assertEquals(RejectionLog.MAX_LINES_PER_WINDOW - 1, lines.size());
        assertEquals(List.of(line("10.1.9.9")), logged(() -> log.rejected("10.1.9.9", "bad token")),
                "the M-th address still gets its line");
        assertEquals(List.of(), logged(() -> log.rejected("10.1.9.10", "bad token")), "the M+1-th does not");
    }

    @Test
    void theSummaryAfterTheWindowCarriesEverySuppressedRequestAndComesFromAnyAddress() {
        for (int i = 0; i < RejectionLog.MAX_LINES_PER_WINDOW + 5; i++) {
            log.rejected("2001:db8::" + i, "bad token"); // 5 over the budget
        }
        log.rejected("2001:db8::0", "bad token"); // a repeat of a logged address
        log.rejected("2001:db8::" + 1000, "bad token"); // another new one, over the budget

        after(60);
        assertEquals(List.of(SUMMARY + " [7, 60]", line("198.51.100.1")),
                logged(() -> log.rejected("198.51.100.1", "bad token")), "a different address closes the window");
        after(120);
        assertEquals(List.of(line("198.51.100.2")), logged(() -> log.rejected("198.51.100.2", "bad token")),
                "the count was reset");
    }

    @Test
    void theBudgetStartsAgainInTheNextWindow() {
        for (int i = 0; i < RejectionLog.MAX_LINES_PER_WINDOW; i++) {
            log.rejected("10.2.0." + i, "bad token");
        }
        after(59);
        assertEquals(List.of(), logged(() -> log.rejected("10.2.1.1", "bad token")));
        after(60);
        List<String> lines = logged(() -> {
            for (int i = 0; i < RejectionLog.MAX_LINES_PER_WINDOW; i++) {
                log.rejected("10.3.0." + i, "bad token");
            }
        });
        assertEquals(RejectionLog.MAX_LINES_PER_WINDOW + 1, lines.size(), "one summary and a full budget");
        assertEquals(SUMMARY + " [1, 60]", lines.get(0));
    }

    @Test
    void noRequestIsLostOverManyWindowsAndAddresses() {
        int windows = 60;
        int perWindow = RejectionLog.MAX_LINES_PER_WINDOW * 3;
        long[] counted = new long[2];
        List<String> all = logged(() -> {
            for (int w = 0; w < windows; w++) {
                after(w * 60L);
                for (int i = 0; i < perWindow; i++) {
                    log.rejected("10." + w + ".0." + (i % (perWindow - 1)), "bad token"); // one repeat per window
                }
            }
            after(windows * 60L);
            log.rejected("192.0.2.1", "bad token");
        });
        for (String entry : all) {
            if (entry.startsWith(SUMMARY)) {
                counted[1] += Long.parseLong(entry.substring(entry.indexOf('[') + 1, entry.indexOf(',')));
            } else {
                counted[0]++;
            }
        }
        long total = (long) windows * perWindow + 1;
        assertEquals(total, counted[0] + counted[1], "every request is either logged or counted in a summary");
        assertEquals((long) windows * RejectionLog.MAX_LINES_PER_WINDOW + 1, counted[0]);
    }
}
