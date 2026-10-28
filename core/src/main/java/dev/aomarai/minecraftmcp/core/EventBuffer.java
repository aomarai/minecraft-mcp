package dev.aomarai.minecraftmcp.core;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The adapter's side of an {@link EventFeed}: publish events into a bounded buffer that keeps the
 * newest {@code capacity} and discards the oldest. A slow, absent or misbehaving client can therefore
 * never make the server hold more than that.
 *
 * <p>Text is cleaned on the way in: control characters (including line breaks) become spaces and anything
 * over {@value #MAX_TEXT} characters is cut, so a payload is always small and on one line. The text is
 * still untrusted: this makes it safe to carry, not safe to obey.</p>
 */
public final class EventBuffer implements EventFeed {

    static final int MAX_TEXT = 256;
    static final int MAX_PLAYER = 64;

    private static final System.Logger LOG = System.getLogger(EventBuffer.class.getName());

    private final int capacity;
    private final Clock clock;
    private final ArrayDeque<GameEvent> events = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final String run = UUID.randomUUID().toString();
    private long latest;

    public EventBuffer(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    EventBuffer(int capacity, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    /** Safe to call from the game thread: it only takes a short lock and never does I/O. */
    public void publish(EventType type, String player, String text) {
        Objects.requireNonNull(type, "type");
        String cleanText = type.hasText() ? UntrustedText.clean(text, MAX_TEXT) : "";
        String cleanPlayer = UntrustedText.clean(player, MAX_PLAYER);
        synchronized (this) {
            latest++;
            events.addLast(new GameEvent(latest, clock.instant(), type, cleanPlayer, cleanText));
            if (events.size() > capacity) {
                events.removeFirst();
            }
        }
        for (Runnable listener : listeners) {
            notifyListener(listener);
        }
    }

    @Override
    public synchronized EventPage read(long after, int limit, String sentRun) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        List<GameEvent> all = List.copyOf(events);
        // Sequences start at 1 in every run, so a cursor from another run can be behind the feed and look fine.
        boolean reset = after > latest || sentRun != null && !sentRun.equals(run);
        if (after < 0 || reset) {
            return new EventPage(all.subList(Math.max(0, all.size() - limit), all.size()), latest, 0, reset, run);
        }
        // Whatever lies between the cursor and the oldest kept event was thrown away before it was read.
        long dropped = all.isEmpty() ? 0 : Math.max(0, all.get(0).sequence() - (after + 1));
        List<GameEvent> newer = all.stream().filter(event -> event.sequence() > after).limit(limit).toList();
        return new EventPage(newer, latest, dropped, false, run);
    }

    @Override
    public Runnable onPublish(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private static void notifyListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            // A broken listener must never reach the game thread that is publishing.
            LOG.log(System.Logger.Level.WARNING, "event listener failed: {0}", e.toString());
        }
    }
}
