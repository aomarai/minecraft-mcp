package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.atomic.AtomicInteger;

/** An {@link EventFeed} that counts its subscribers, to prove they are removed. */
final class CountingFeed implements EventFeed {

    private final EventFeed delegate;
    private final AtomicInteger listeners = new AtomicInteger();

    CountingFeed(EventFeed delegate) {
        this.delegate = delegate;
    }

    int listeners() {
        return listeners.get();
    }

    @Override
    public EventPage read(long after, int limit, String run) {
        return delegate.read(after, limit, run);
    }

    @Override
    public Runnable onPublish(Runnable listener) {
        listeners.incrementAndGet();
        Runnable remove = delegate.onPublish(listener);
        return () -> {
            listeners.decrementAndGet();
            remove.run();
        };
    }
}
