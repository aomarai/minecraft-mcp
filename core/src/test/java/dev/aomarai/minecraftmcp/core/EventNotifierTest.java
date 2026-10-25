package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventNotifierTest {

    private final EventBuffer feed = new EventBuffer(10);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    /** Returns once every task queued before this call has run. */
    private void drain() throws Exception {
        executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void everyEventAfterTheLastNotificationWasSentCausesAnother() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        try (EventNotifier ignored = new EventNotifier(feed, executor, sent::incrementAndGet)) {
            feed.publish(EventType.JOIN, "Steve", "");
            drain();
            assertEquals(1, sent.get());

            feed.publish(EventType.JOIN, "Alex", "");
            drain();
            assertEquals(2, sent.get());
        }
    }

    @Test
    void aBurstOfEventsWhileOneIsBeingSentCollapsesIntoOneMoreNotification() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        Runnable notification = () -> {
            if (sent.incrementAndGet() == 1) {
                firstStarted.countDown();
                await(release);
            }
        };
        try (EventNotifier ignored = new EventNotifier(feed, executor, notification)) {
            feed.publish(EventType.CHAT, "Steve", "1");
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS));

            feed.publish(EventType.CHAT, "Steve", "2");
            feed.publish(EventType.CHAT, "Steve", "3");
            feed.publish(EventType.CHAT, "Steve", "4");
            release.countDown();
            drain();

            assertEquals(2, sent.get(), "the three events published during the first send share one notification");
        }
    }

    @Test
    void publishingNeverWaitsForTheNotification() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try (EventNotifier ignored = new EventNotifier(feed, executor, () -> {
            started.countDown();
            await(release);
        })) {
            feed.publish(EventType.CHAT, "Steve", "1");
            assertTrue(started.await(10, TimeUnit.SECONDS));

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                    () -> feed.publish(EventType.CHAT, "Steve", "2"));
            release.countDown();
        }
    }

    @Test
    void aFailingNotificationIsLoggedAndTheNextEventStillNotifies() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        try (LogCapture logs = new LogCapture();
             EventNotifier ignored = new EventNotifier(feed, executor, () -> {
                 if (sent.incrementAndGet() == 1) {
                     throw new IllegalStateException("client went away");
                 }
             })) {
            feed.publish(EventType.JOIN, "Steve", "");
            drain();
            assertEquals(1, logs.lines().size(), logs.lines().toString());
            assertTrue(logs.lines().get(0).contains("event notification failed"), logs.lines().get(0));
            assertTrue(logs.lines().get(0).contains("client went away"), logs.lines().get(0));

            feed.publish(EventType.JOIN, "Alex", "");
            drain();

            assertEquals(2, sent.get());
            assertEquals(1, logs.lines().size());
        }
    }

    @Test
    void closingStopsListeningAndShutsTheExecutorDown() throws Exception {
        CountingFeed counting = new CountingFeed(feed);
        AtomicInteger sent = new AtomicInteger();
        EventNotifier notifier = new EventNotifier(counting, executor, sent::incrementAndGet);
        assertEquals(1, counting.listeners());

        notifier.close();
        feed.publish(EventType.JOIN, "Steve", "");

        assertEquals(0, counting.listeners());
        assertTrue(executor.isShutdown());
        assertEquals(0, sent.get());
    }

    @Test
    void theDaemonNotifierRunsOnItsOwnDaemonThread() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        Thread[] where = new Thread[1];
        try (EventNotifier ignored = EventNotifier.onDaemonThread(feed, () -> {
            where[0] = Thread.currentThread();
            ran.countDown();
        })) {
            feed.publish(EventType.JOIN, "Steve", "");

            assertTrue(ran.await(10, TimeUnit.SECONDS));
            assertTrue(where[0].isDaemon());
            assertEquals("minecraft-mcp-events", where[0].getName());
            assertNotSame(Thread.currentThread(), where[0]);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
