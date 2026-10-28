package dev.aomarai.minecraftmcp.core;

/**
 * A bounded record of recent game events (chat, joins, leaves, deaths) that clients read by cursor.
 *
 * <p>The adapter publishes on the game thread; MCP clients read from HTTP threads. Neither waits for
 * the other, and nothing here can grow without limit.</p>
 */
public interface EventFeed extends GamePort {

    int MAX_LIMIT = 200;

    /**
     * Events with a sequence greater than {@code after}, oldest first, at most {@code limit} of them.
     * A negative {@code after} means "the newest {@code limit} events". {@code run} is the {@link EventPage#run()} the
     * cursor came from, or null if the client has none: a cursor from another run is answered as a reset.
     */
    EventPage read(long after, int limit, String run);

    /** As {@link #read(long, int, String)} for a client that has no run id, so only a cursor ahead of the feed is a reset. */
    default EventPage read(long after, int limit) {
        return read(after, limit, null);
    }

    /**
     * Calls {@code listener} after every event is published. It must be quick and must not block: it
     * runs on the game thread. Returns a handle that removes the listener when run.
     */
    Runnable onPublish(Runnable listener);
}
