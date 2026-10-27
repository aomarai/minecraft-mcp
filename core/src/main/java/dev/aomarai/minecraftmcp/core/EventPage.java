package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * Some events and where the feed stands.
 *
 * @param latest  the newest sequence published so far, 0 if nothing has happened yet
 * @param dropped how many events after the cursor were discarded before they could be read, because the
 *                feed only keeps the newest ones
 * @param reset   the cursor was ahead of the feed, so it came from an earlier run (sequences start at 1 in every
 *                process); the page then holds the newest events, as for a reader with no cursor
 */
public record EventPage(List<GameEvent> events, long latest, long dropped, boolean reset) {

    public EventPage {
        events = List.copyOf(events);
    }
}
