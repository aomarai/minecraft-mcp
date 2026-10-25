package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.EntityList;
import dev.aomarai.minecraftmcp.core.EntityQuery;
import dev.aomarai.minecraftmcp.core.EntityReader;
import dev.aomarai.minecraftmcp.core.MainThread;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Entities within a radius, read on the main thread. Only entities in loaded chunks are seen; no
 * chunk is loaded or generated. Entity types are matched and reported by registry id, so modded
 * entities work unchanged.
 */
final class FabricEntityReader implements EntityReader {

    private final MinecraftServer server;

    FabricEntityReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<EntityList> entitiesNear(EntityQuery query) {
        return MainThread.call(server, () -> find(FabricDimensions.level(server, query.dimension()), query));
    }

    private static EntityList find(ServerLevel level, EntityQuery query) {
        Vec3 centre = new Vec3(query.x(), query.y(), query.z());
        double radiusSquared = query.radius() * query.radius();
        ResourceLocation type = query.type().isEmpty() ? null : ResourceLocation.parse(query.type());

        List<Entity> nearest = level.getEntities((Entity) null, new AABB(centre, centre).inflate(query.radius()),
                        entity -> entity.position().distanceToSqr(centre) <= radiusSquared
                                && (type == null || type.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))))
                .stream()
                .sorted(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(centre)))
                .limit(query.limit() + 1L)
                .toList();

        List<EntityList.EntityInfo> entities = nearest.stream()
                .limit(query.limit())
                .map(FabricEntityReader::describe)
                .toList();
        return new EntityList(query.dimension(), entities, nearest.size() > query.limit());
    }

    private static EntityList.EntityInfo describe(Entity entity) {
        return new EntityList.EntityInfo(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                entity.getUUID().toString(),
                entity.getName().getString(),
                entity.getX(), entity.getY(), entity.getZ());
    }
}
