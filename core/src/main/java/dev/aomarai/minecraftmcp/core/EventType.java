package dev.aomarai.minecraftmcp.core;

import java.util.Locale;

/** What happened in the game. */
public enum EventType {
    CHAT(true),
    JOIN(false),
    LEAVE(false),
    DEATH(true);

    private final boolean hasText;

    EventType(boolean hasText) {
        this.hasText = hasText;
    }

    /**
     * Whether events of this type carry text a player wrote or influenced (a chat message, a death message
     * that names players and mobs). That text is untrusted.
     */
    public boolean hasText() {
        return hasText;
    }

    /** The lower-case name used in tool results. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
