package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * One page of a registry listing.
 *
 * @param total   how many ids match the query in all
 * @param hasMore whether ids remain after this page (continue at {@code offset + ids.size()})
 */
public record RegistryPage(String registry, List<String> ids, int total, int offset, boolean hasMore) {

    public RegistryPage {
        ids = List.copyOf(ids);
    }
}
