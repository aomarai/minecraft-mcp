package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * Some events and where the feed stands.
 *
 * @param latest  the newest sequence published so far, 0 if nothing has happened yet
 * @param dropped how many events after the cursor were discarded before they could be read, because the
 *                feed only keeps the newest ones
 */
public record EventPage(List<GameEvent> events, long latest, long dropped) {

    public EventPage {
        events = List.copyOf(events);
    }
}
