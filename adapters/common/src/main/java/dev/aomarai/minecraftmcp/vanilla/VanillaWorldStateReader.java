package dev.aomarai.minecraftmcp.vanilla;

import dev.aomarai.minecraftmcp.core.MainThread;
import dev.aomarai.minecraftmcp.core.WorldState;
import dev.aomarai.minecraftmcp.core.WorldStateReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * A dimension's time, weather, difficulty and game rules, read on the main thread. The game rules
 * are enumerated from the game, so rules added by mods are included.
 */
public final class VanillaWorldStateReader implements WorldStateReader {

    private final MinecraftServer server;

    public VanillaWorldStateReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<WorldState> state(String dimension) {
        return MainThread.call(server, () -> describe(dimension, VanillaDimensions.level(server, dimension)));
    }

    private static WorldState describe(String dimension, ServerLevel level) {
        return new WorldState(dimension, level.getGameTime(), level.getDayTime(), level.isRaining(),
                level.isThundering(), level.getDifficulty().getKey(), gameRules(level.getGameRules()));
    }

    private static Map<String, String> gameRules(GameRules rules) {
        Map<String, String> values = new TreeMap<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                values.put(key.getId(), rules.getRule(key).serialize());
            }
        });
        return values;
    }
}
