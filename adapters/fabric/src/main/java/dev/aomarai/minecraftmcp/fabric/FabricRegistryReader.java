package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.MainThread;
import dev.aomarai.minecraftmcp.core.RegistryPage;
import dev.aomarai.minecraftmcp.core.RegistryQuery;
import dev.aomarai.minecraftmcp.core.RegistryReader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lists any registry by its id: the built-in ones (blocks, items, entity types, ...) and the
 * dynamic ones the server holds (biomes, dimension types, ...). Whatever mods register shows up
 * without any code here naming it.
 */
final class FabricRegistryReader implements RegistryReader {

    private final MinecraftServer server;

    FabricRegistryReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<RegistryPage> list(RegistryQuery query) {
        return MainThread.call(server, () -> page(registry(query.registry()), query));
    }

    private static RegistryPage page(Registry<?> registry, RegistryQuery query) {
        String search = query.search().toLowerCase(Locale.ROOT);
        List<String> matching = registry.keySet().stream()
                .map(ResourceLocation::toString)
                .filter(id -> search.isEmpty() || id.toLowerCase(Locale.ROOT).contains(search))
                .sorted()
                .toList();
        int from = Math.min(query.offset(), matching.size());
        int to = Math.min(matching.size(), from + query.limit());
        return new RegistryPage(query.registry(), matching.subList(from, to), matching.size(), query.offset(),
                to < matching.size());
    }

    private Registry<?> registry(String id) {
        ResourceLocation location = ResourceLocation.parse(id);
        Registry<?> builtIn = BuiltInRegistries.REGISTRY.get(location);
        if (builtIn != null) {
            return builtIn;
        }
        Registry<?> dynamic = server.registryAccess()
                .registry(ResourceKey.<Object>createRegistryKey(location)).orElse(null);
        if (dynamic == null) {
            throw new IllegalArgumentException("unknown registry '" + id + "'; available: " + available());
        }
        return dynamic;
    }

    private String available() {
        return Stream.concat(
                        BuiltInRegistries.REGISTRY.keySet().stream(),
                        server.registryAccess().registries().map(entry -> entry.key().location()))
                .map(ResourceLocation::toString)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
