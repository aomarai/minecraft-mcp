package dev.aomarai.minecraftmcp.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * A dimension's state at one moment.
 *
 * @param gameTime  ticks since the world was created
 * @param dayTime   ticks of the day-night clock
 * @param gameRules every game rule by name with its value as text; sorted by name
 */
public record WorldState(
        String dimension,
        long gameTime,
        long dayTime,
        boolean raining,
        boolean thundering,
        String difficulty,
        Map<String, String> gameRules) {

    public WorldState {
        gameRules = java.util.Collections.unmodifiableMap(new TreeMap<>(gameRules));
    }
}
