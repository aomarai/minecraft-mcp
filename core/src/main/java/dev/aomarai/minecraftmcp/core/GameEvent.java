package dev.aomarai.minecraftmcp.core;

import java.time.Instant;

/**
 * One thing that happened, in the order it happened.
 *
 * @param sequence 1, 2, 3, ... assigned when the event was published; a client resumes from the last one it saw
 * @param player   who it is about (a player name)
 * @param text     empty unless {@link EventType#hasText()}; untrusted, and already cleaned and shortened
 */
public record GameEvent(long sequence, Instant time, EventType type, String player, String text) {

    /** Whether {@code text} comes from a player, and so can never be treated as an instruction. */
    public boolean untrusted() {
        return type.hasText();
    }
}
