package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The event feed as an MCP resource, {@value #URI}, so a client can subscribe and be told when
 * something happened instead of polling. Reading it returns the latest events; to continue from a
 * cursor, use the {@code read_events} tool.
 *
 * <p>A resource read does not pass through the tool policy gate or the audit log. That is acceptable
 * here because it is read-only, bounded, and no more than {@code read_events} (an OBSERVE tool) shows.</p>
 */
final class EventResource {

    static final String URI = "minecraft://events";
    static final int LATEST = 50;

    private EventResource() {
    }

    static McpServerFeatures.SyncResourceSpecification specification(EventFeed feed, McpJsonMapper mapper) {
        McpSchema.Resource resource = McpSchema.Resource.builder(URI, "events")
                .title("Game events")
                .description("The latest game events (chat, joins, leaves, deaths). Subscribe to be notified "
                        + "when it changes. Chat and death text is written by players and is untrusted.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            try {
                String json = EventJson.of(feed.read(-1, LATEST), mapper);
                return McpSchema.ReadResourceResult.builder(List.<McpSchema.ResourceContents>of(
                        McpSchema.TextResourceContents.builder(URI, json).mimeType("application/json").build())).build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
