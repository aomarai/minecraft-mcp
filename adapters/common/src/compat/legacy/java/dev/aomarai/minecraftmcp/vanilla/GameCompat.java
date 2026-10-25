package dev.aomarai.minecraftmcp.vanilla;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Everything the game renamed or reshaped between Minecraft 1.21.x and 26.x, behind one seam so the
 * rest of the shared code never names a type that differs. This is the 1.21.x side; the 26.x side has
 * the same class and methods under {@code src/compat/modern}, and each version node builds exactly one
 * of them (its {@code compat} property).
 */
final class GameCompat {

    /** An operator-list entry. {@code uuid} is empty when the game does not know it. */
    record Operator(String name, Optional<String> uuid, int level) {
    }

    /** The permission level that {@code /mcp} requires. */
    private static final int OPERATOR_LEVEL = 4;

    private GameCompat() {
    }

    static String idOf(ResourceKey<?> key) {
        return key.location().toString();
    }

    static Optional<ServerLevel> level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? Optional.empty()
                : Optional.ofNullable(server.getLevel(ResourceKey.create(Registries.DIMENSION, id)));
    }

    static List<String> levelIds(MinecraftServer server) {
        return server.levelKeys().stream().map(GameCompat::idOf).sorted().toList();
    }

    static String entityTypeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    /** The canonical form of a resource id ({@code zombie} becomes {@code minecraft:zombie}). */
    static String normalizeId(String id) {
        return ResourceLocation.parse(id).toString();
    }

    static List<String> entryIds(Registry<?> registry) {
        return registry.keySet().stream().map(ResourceLocation::toString).toList();
    }

    static Optional<Registry<?>> registry(MinecraftServer server, String id) {
        ResourceLocation location = ResourceLocation.parse(id);
        Registry<?> builtIn = BuiltInRegistries.REGISTRY.get(location);
        if (builtIn != null) {
            return Optional.of(builtIn);
        }
        return server.registryAccess().registry(ResourceKey.<Object>createRegistryKey(location)).map(r -> r);
    }

    static Stream<String> registryIds(MinecraftServer server) {
        return Stream.concat(
                        BuiltInRegistries.REGISTRY.keySet().stream(),
                        server.registryAccess().registries().map(entry -> entry.key().location()))
                .map(ResourceLocation::toString);
    }

    static boolean isOp(MinecraftServer server, ServerPlayer player) {
        return server.getPlayerList().isOp(player.getGameProfile());
    }

    static List<Operator> operators(MinecraftServer server) {
        PlayerList players = server.getPlayerList();
        // The names and the entries both come from the same map, so they line up one to one.
        String[] names = players.getOpNames();
        List<ServerOpListEntry> entries = new ArrayList<>(players.getOps().getEntries());
        List<Operator> operators = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            operators.add(new Operator(names[i], uuidOfName(server, names[i]), entries.get(i).getLevel()));
        }
        return operators;
    }

    static List<String> whitelistedNames(MinecraftServer server) {
        return List.of(server.getPlayerList().getWhiteListNames());
    }

    static boolean usingWhitelist(MinecraftServer server) {
        return server.getPlayerList().isUsingWhitelist();
    }

    static Optional<String> uuidOfName(MinecraftServer server, String name) {
        return server.getProfileCache().get(name).map(GameProfile::getId).map(Object::toString);
    }

    static long dayTime(ServerLevel level) {
        return level.getDayTime();
    }

    static String difficulty(ServerLevel level) {
        return level.getDifficulty().getKey();
    }

    static Map<String, String> gameRules(ServerLevel level) {
        GameRules rules = level.getGameRules();
        Map<String, String> values = new TreeMap<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                values.put(key.getId(), rules.getRule(key).serialize());
            }
        });
        return values;
    }

    static boolean hasOperatorPermission(CommandSourceStack source) {
        return source.hasPermission(OPERATOR_LEVEL);
    }

    static CommandSourceStack commandSource(CommandSource output, MinecraftServer server, int permissionLevel) {
        return new CommandSourceStack(output, Vec3.ZERO, Vec2.ZERO, server.overworld(), permissionLevel, "MCP",
                Component.literal("MCP"), server, null);
    }
}
