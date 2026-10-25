package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells subscribed clients that the event feed changed, without ever making the game thread wait.
 * Publishing only flips a flag and queues one task on a private daemon thread; a burst of events
 * collapses into one notification, because the client reads the feed to see all of them.
 */
final class EventNotifier implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EventNotifier.class.getName());

    private final ExecutorService executor;
    private final Runnable notification;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Runnable unsubscribe;

    /** Notifies on its own daemon thread, so it can never keep the JVM alive. */
    static EventNotifier onDaemonThread(EventFeed feed, Runnable notification) {
        return new EventNotifier(feed, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "minecraft-mcp-events");
            thread.setDaemon(true);
            return thread;
        }), notification);
    }

    /** @param executor must run tasks one at a time; shut down by {@link #close()} */
    EventNotifier(EventFeed feed, ExecutorService executor, Runnable notification) {
        this.executor = executor;
        this.notification = notification;
        this.unsubscribe = feed.onPublish(this::schedule);
    }

    private void schedule() {
        if (pending.compareAndSet(false, true)) {
            executor.execute(this::send);
        }
    }

    private void send() {
        pending.set(false);
        try {
            notification.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "event notification failed: {0}", e.toString());
        }
    }

    @Override
    public void close() {
        unsubscribe.run();
        executor.shutdownNow();
    }
}
