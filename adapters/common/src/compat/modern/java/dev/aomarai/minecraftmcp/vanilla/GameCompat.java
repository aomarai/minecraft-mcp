package dev.aomarai.minecraftmcp.vanilla;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
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
 * rest of the shared code never names a type that differs. This is the 26.x side; the 1.21.x side has
 * the same class and methods under {@code src/compat/legacy}, and each version node builds exactly one
 * of them (its {@code compat} property).
 */
final class GameCompat {

    /** An operator-list entry. {@code uuid} is empty when the game does not know it. */
    record Operator(String name, Optional<String> uuid, int level) {
    }

    private GameCompat() {
    }

    static String idOf(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    static Optional<ServerLevel> level(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
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
        return Identifier.parse(id).toString();
    }

    static List<String> entryIds(Registry<?> registry) {
        return registry.keySet().stream().map(Identifier::toString).toList();
    }

    static Optional<Registry<?>> registry(MinecraftServer server, String id) {
        Identifier location = Identifier.parse(id);
        Registry<?> builtIn = BuiltInRegistries.REGISTRY.getValue(location);
        if (builtIn != null) {
            return Optional.of(builtIn);
        }
        return server.registryAccess().lookup(ResourceKey.<Object>createRegistryKey(location)).map(r -> r);
    }

    static Stream<String> registryIds(MinecraftServer server) {
        return Stream.concat(
                        BuiltInRegistries.REGISTRY.keySet().stream(),
                        server.registryAccess().registries().map(entry -> entry.key().identifier()))
                .map(Identifier::toString);
    }

    static boolean isOp(MinecraftServer server, ServerPlayer player) {
        return server.getPlayerList().isOp(new NameAndId(player.getGameProfile()));
    }

    static List<Operator> operators(MinecraftServer server) {
        List<Operator> operators = new ArrayList<>();
        for (ServerOpListEntry entry : server.getPlayerList().getOps().getEntries()) {
            NameAndId user = entry.getUser();
            operators.add(new Operator(user.name(), Optional.of(user.id().toString()),
                    entry.permissions().level().id()));
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
        return server.services().nameToIdCache().get(name).map(NameAndId::id).map(Object::toString);
    }

    static long dayTime(ServerLevel level) {
        return level.getDefaultClockTime();
    }

    static String difficulty(ServerLevel level) {
        return level.getDifficulty().getSerializedName();
    }

    static Map<String, String> gameRules(ServerLevel level) {
        GameRules rules = level.getGameRules();
        Map<String, String> values = new TreeMap<>();
        rules.availableRules().forEach(rule -> values.put(rule.id(), valueOf(rules, rule)));
        return values;
    }

    private static <T> String valueOf(GameRules rules, GameRule<T> rule) {
        return rules.getAsString(rule);
    }

    static boolean hasOperatorPermission(CommandSourceStack source) {
        return source.permissions() instanceof LevelBasedPermissionSet levelBased
                && levelBased.level().isEqualOrHigherThan(PermissionLevel.OWNERS);
    }

    static CommandSourceStack commandSource(CommandSource output, MinecraftServer server, int permissionLevel) {
        // The constructor changed again between 26.2 and 26.3, so start from the server's own source.
        return server.createCommandSourceStack()
                .withSource(output)
                .withPosition(Vec3.ZERO)
                .withRotation(Vec2.ZERO)
                .withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(permissionLevel)));
    }
}
