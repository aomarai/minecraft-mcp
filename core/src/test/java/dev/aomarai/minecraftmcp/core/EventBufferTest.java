package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventBufferTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private final EventBuffer buffer = new EventBuffer(3, Clock.fixed(NOW, ZoneOffset.UTC));

    private static List<Long> sequences(EventPage page) {
        return page.events().stream().map(GameEvent::sequence).toList();
    }

    @Test
    void anEmptyFeedHasNothingAndNothingDropped() {
        EventPage page = buffer.read(-1, 10);
        assertEquals(List.of(), page.events());
        assertEquals(0, page.latest());
        assertEquals(0, page.dropped());
        assertEquals(0, buffer.read(0, 10).dropped());
    }

    @Test
    void aCursorAheadOfTheFeedIsFromAnEarlierRunAndGetsTheNewestEventsWithAReset() {
        for (int i = 1; i <= 5; i++) {
            buffer.publish(EventType.CHAT, "Steve", "m" + i);
        }

        EventPage behind = buffer.read(4, 10);
        assertEquals(List.of(5L), sequences(behind));
        assertFalse(behind.reset());

        EventPage caughtUp = buffer.read(5, 10);
        assertEquals(List.of(), sequences(caughtUp));
        assertFalse(caughtUp.reset());

        EventPage ahead = buffer.read(6, 2);
        assertEquals(List.of(4L, 5L), sequences(ahead));
        assertEquals(5, ahead.latest());
        assertEquals(0, ahead.dropped());
        assertTrue(ahead.reset());
    }

    @Test
    void aStaleCursorOnAnEmptyFeedIsAResetAndNegativeOrZeroCursorsAreNot() {
        EventPage stale = buffer.read(450, 10);
        assertEquals(List.of(), stale.events());
        assertTrue(stale.reset());

        assertFalse(buffer.read(0, 10).reset());
        assertFalse(buffer.read(-1, 10).reset());
    }

    @Test
    void eventsAreNumberedFromOneInOrderAndStamped() {
        buffer.publish(EventType.JOIN, "Steve", "");
        buffer.publish(EventType.CHAT, "Steve", "hi");

        EventPage page = buffer.read(-1, 10);

        assertEquals(List.of(1L, 2L), sequences(page));
        assertEquals(2, page.latest());
        GameEvent chat = page.events().get(1);
        assertEquals(new GameEvent(2, NOW, EventType.CHAT, "Steve", "hi"), chat);
        assertTrue(chat.untrusted());
        assertFalse(page.events().get(0).untrusted());
    }

    @Test
    void theFeedKeepsOnlyTheNewestAndSaysHowManyAReaderMissed() {
        for (int i = 1; i <= 5; i++) {
            buffer.publish(EventType.CHAT, "Steve", "m" + i);
        }

        EventPage fromStart = buffer.read(0, 10);
        assertEquals(List.of(3L, 4L, 5L), sequences(fromStart));
        assertEquals(2, fromStart.dropped());

        EventPage fromOne = buffer.read(1, 10);
        assertEquals(List.of(3L, 4L, 5L), sequences(fromOne));
        assertEquals(1, fromOne.dropped());

        EventPage fromTwo = buffer.read(2, 10);
        assertEquals(List.of(3L, 4L, 5L), sequences(fromTwo));
        assertEquals(0, fromTwo.dropped());
    }

    @Test
    void aReaderThatIsUpToDateGetsNothingAndNothingDropped() {
        buffer.publish(EventType.CHAT, "Steve", "a");
        buffer.publish(EventType.CHAT, "Steve", "b");

        EventPage page = buffer.read(2, 10);

        assertEquals(List.of(), page.events());
        assertEquals(2, page.latest());
        assertEquals(0, page.dropped());
    }

    @Test
    void whenEverythingWasDroppedTheCountStillCoversTheGap() {
        EventBuffer one = new EventBuffer(1);
        for (int i = 0; i < 4; i++) {
            one.publish(EventType.JOIN, "Steve", "");
        }
        assertEquals(3, one.read(0, 5).dropped());
        assertEquals(List.of(4L), sequences(one.read(0, 5)));
    }

    @Test
    void theLimitCapsAPageFromTheOldestNewerEventOrTheNewestWithoutACursor() {
        for (int i = 1; i <= 3; i++) {
            buffer.publish(EventType.CHAT, "Steve", "m" + i);
        }
        assertEquals(List.of(1L, 2L), sequences(buffer.read(0, 2)));
        assertEquals(List.of(2L, 3L), sequences(buffer.read(-1, 2)));
        assertEquals(List.of(1L, 2L, 3L), sequences(buffer.read(-1, 3)));
    }

    @Test
    void theLimitIsBounded() {
        assertEquals("limit must be between 1 and 200", assertThrows(IllegalArgumentException.class,
                () -> buffer.read(-1, 0)).getMessage());
        assertEquals("limit must be between 1 and 200", assertThrows(IllegalArgumentException.class,
                () -> buffer.read(-1, 201)).getMessage());
        assertDoesNotThrow(() -> buffer.read(-1, 1));
        assertDoesNotThrow(() -> buffer.read(-1, 200));
    }

    @Test
    void capacityMustBePositive() {
        assertEquals("capacity must be at least 1", assertThrows(IllegalArgumentException.class,
                () -> new EventBuffer(0)).getMessage());
        assertDoesNotThrow(() -> new EventBuffer(1));
    }

    @Test
    void textIsOnlyKeptForTypesThatCarryIt() {
        buffer.publish(EventType.JOIN, "Steve", "should not be kept");
        buffer.publish(EventType.LEAVE, "Steve", "nor this");
        buffer.publish(EventType.DEATH, "Steve", "Steve fell");

        List<GameEvent> events = buffer.read(-1, 3).events();

        assertEquals("", events.get(0).text());
        assertEquals("", events.get(1).text());
        assertEquals("Steve fell", events.get(2).text());
    }

    @Test
    void controlCharactersBecomeSpacesAndTextIsTrimmed() {
        buffer.publish(EventType.CHAT, "St\neve", "  line one\nline\ttwo\u0000end\u001b[31m  ");

        GameEvent event = buffer.read(-1, 1).events().get(0);

        assertEquals("St eve", event.player());
        assertEquals("line one line two end [31m", event.text());
    }

    @Test
    void longTextAndNamesAreCutAtTheirLimitsWithAnEllipsis() {
        buffer.publish(EventType.CHAT, "p".repeat(65), "x".repeat(257));
        buffer.publish(EventType.CHAT, "p".repeat(64), "x".repeat(256));

        List<GameEvent> events = buffer.read(-1, 2).events();

        assertEquals("p".repeat(64) + "...", events.get(0).player());
        assertEquals("x".repeat(256) + "...", events.get(0).text());
        assertEquals("p".repeat(64), events.get(1).player());
        assertEquals("x".repeat(256), events.get(1).text());
    }

    @Test
    void missingTextAndNamesAreEmptyNotNull() {
        buffer.publish(EventType.CHAT, null, null);

        GameEvent event = buffer.read(-1, 1).events().get(0);

        assertEquals("", event.player());
        assertEquals("", event.text());
    }

    @Test
    void aMissingTypeIsRejectedByName() {
        assertEquals("type", assertThrows(NullPointerException.class,
                () -> buffer.publish(null, "Steve", "x")).getMessage());
    }

    @Test
    void listenersRunAfterEachPublishAndStopAfterTheyAreRemoved() {
        List<Long> seenLatest = new ArrayList<>();
        Runnable remove = buffer.onPublish(() -> seenLatest.add(buffer.read(-1, 1).latest()));

        buffer.publish(EventType.JOIN, "Steve", "");
        buffer.publish(EventType.JOIN, "Alex", "");
        remove.run();
        buffer.publish(EventType.JOIN, "Sam", "");

        assertEquals(List.of(1L, 2L), seenLatest);
    }

    @Test
    void aBrokenListenerNeitherStopsOthersNorReachesThePublisher() {
        List<String> ran = new ArrayList<>();
        buffer.onPublish(() -> {
            throw new IllegalStateException("boom");
        });
        buffer.onPublish(() -> ran.add("second"));

        try (LogCapture logs = new LogCapture()) {
            assertDoesNotThrow(() -> buffer.publish(EventType.JOIN, "Steve", ""));

            assertEquals(1, logs.lines().size(), logs.lines().toString());
            assertTrue(logs.lines().get(0).contains("event listener failed"), logs.lines().get(0));
            assertTrue(logs.lines().get(0).contains("boom"), logs.lines().get(0));
        }

        assertEquals(List.of("second"), ran);
        assertEquals(List.of(1L), sequences(buffer.read(-1, 5)));
    }

    @Test
    void eventTypesKnowTheirWireNameAndWhetherTheyCarryText() {
        assertEquals("chat", EventType.CHAT.wire());
        assertEquals("death", EventType.DEATH.wire());
        assertTrue(EventType.CHAT.hasText());
        assertTrue(EventType.DEATH.hasText());
        assertFalse(EventType.JOIN.hasText());
        assertFalse(EventType.LEAVE.hasText());
    }

    @Test
    void aPageCannotBeChangedAfterItIsRead() {
        buffer.publish(EventType.JOIN, "Steve", "");
        EventPage page = buffer.read(-1, 5);

        var events2 = page.events();
        assertThrows(UnsupportedOperationException.class, () -> events2.clear());
        buffer.publish(EventType.JOIN, "Alex", "");
        assertEquals(1, page.events().size());
    }

    private void publish(int count) {
        for (int i = 1; i <= count; i++) {
            buffer.publish(EventType.CHAT, "Steve", "m" + i);
        }
    }

    @Test
    void everyPageCarriesTheRunIdOfTheFeedAndTwoFeedsDiffer() {
        publish(3);
        String run = buffer.read(-1, 10).run();

        assertFalse(run.isBlank());
        assertEquals(run, buffer.read(1, 10).run());
        assertEquals(run, buffer.read(99, 10).run(), "a reset page carries it too");
        assertNotEquals(run, new EventBuffer(3, Clock.fixed(NOW, ZoneOffset.UTC)).read(-1, 10).run());
    }

    @Test
    void aCursorFromAnotherRunIsResetEvenWhenTheNewRunHasPassedIt() {
        publish(3); // latest = 3, and the buffer keeps 3

        for (long after : new long[]{2, 3, 4}) { // latest - 1, latest, latest + 1
            EventPage page = buffer.read(after, 10, "an-earlier-run");
            assertTrue(page.reset(), "after=" + after);
            assertEquals(List.of(1L, 2L, 3L), sequences(page), "after=" + after);
            assertEquals(0, page.dropped(), "after=" + after);
            assertEquals(3, page.latest());
        }
        EventPage limited = buffer.read(0, 2, "an-earlier-run");
        assertTrue(limited.reset());
        assertEquals(List.of(2L, 3L), sequences(limited), "the newest events, as for a reader with no cursor");
    }

    @Test
    void aCursorFromThisRunBehavesAsBefore() {
        publish(5); // keeps 3..5, so sequences 1 and 2 were dropped
        String run = buffer.read(-1, 10).run();

        EventPage behind = buffer.read(4, 10, run);
        assertFalse(behind.reset());
        assertEquals(List.of(5L), sequences(behind));
        assertFalse(buffer.read(5, 10, run).reset());
        assertTrue(buffer.read(6, 10, run).reset(), "ahead of the feed is still a reset");
        EventPage old = buffer.read(0, 10, run);
        assertFalse(old.reset());
        assertEquals(2, old.dropped());
    }

    @Test
    void aClientWithNoRunIdKeepsTheOldRule() {
        publish(3);

        assertFalse(buffer.read(2, 10, null).reset());
        assertFalse(buffer.read(3, 10, null).reset());
        assertTrue(buffer.read(4, 10, null).reset());
    }
}
