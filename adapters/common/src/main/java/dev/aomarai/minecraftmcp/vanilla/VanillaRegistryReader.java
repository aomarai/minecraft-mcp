package dev.aomarai.minecraftmcp.vanilla;

import dev.aomarai.minecraftmcp.core.MainThread;
import dev.aomarai.minecraftmcp.core.RegistryPage;
import dev.aomarai.minecraftmcp.core.RegistryQuery;
import dev.aomarai.minecraftmcp.core.RegistryReader;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Lists any registry by its id: the built-in ones (blocks, items, entity types, ...) and the
 * dynamic ones the server holds (biomes, dimension types, ...). Whatever mods register shows up
 * without any code here naming it.
 */
public final class VanillaRegistryReader implements RegistryReader {

    private final MinecraftServer server;

    public VanillaRegistryReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<RegistryPage> list(RegistryQuery query) {
        return MainThread.call(server, () -> page(registry(query.registry()), query));
    }

    private static RegistryPage page(Registry<?> registry, RegistryQuery query) {
        String search = query.search().toLowerCase(Locale.ROOT);
        List<String> matching = GameCompat.entryIds(registry).stream()
                .filter(id -> search.isEmpty() || id.toLowerCase(Locale.ROOT).contains(search))
                .sorted()
                .toList();
        int from = Math.min(query.offset(), matching.size());
        int to = Math.min(matching.size(), from + query.limit());
        return new RegistryPage(query.registry(), matching.subList(from, to), matching.size(), query.offset(),
                to < matching.size());
    }

    private Registry<?> registry(String id) {
        return GameCompat.registry(server, id).orElseThrow(
                () -> new IllegalArgumentException("unknown registry '" + id + "'; available: " + available()));
    }

    private String available() {
        return GameCompat.registryIds(server)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
