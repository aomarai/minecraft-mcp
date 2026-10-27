package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * Entities found by an {@link EntityQuery}, nearest first.
 *
 * @param truncated whether more entities matched than the limit allowed
 */
public record EntityList(String dimension, List<EntityInfo> entities, boolean truncated) {

    public EntityList {
        entities = List.copyOf(entities);
    }

    /**
     * One entity. {@code name} is whatever the entity is called in the game: for players and named
     * mobs that is player-controlled text, so it is untrusted and can never authorize anything. It is cleaned
     * (control characters become spaces) and cut at {@value #MAX_NAME} characters.
     */
    public record EntityInfo(String type, String uuid, String name, double x, double y, double z) {

        public static final int MAX_NAME = 256;

        public EntityInfo {
            name = UntrustedText.clean(name, MAX_NAME);
        }
    }
}
