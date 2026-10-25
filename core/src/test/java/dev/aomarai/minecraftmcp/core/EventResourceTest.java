package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EventResourceTest {

    private static String text(McpSchema.ReadResourceResult result) {
        return ((McpSchema.TextResourceContents) result.contents().get(0)).text();
    }

    @Test
    void aCoreWithAnEventFeedAdvertisesASubscribableEventsResource() throws Exception {
        FakeGame game = new FakeGame();
        game.events.publish(EventType.CHAT, "Steve", "hello");
        try (McpCore core = TestCores.full(game)) {
            core.start();
            try (McpSyncClient client = McpTestClients.client(core)) {
                McpSchema.InitializeResult init = client.initialize();
                assertEquals(Boolean.TRUE, init.capabilities().resources().subscribe());
                assertNotEquals(Boolean.TRUE, init.capabilities().resources().listChanged());

                List<McpSchema.Resource> resources = client.listResources().resources();
                assertEquals(1, resources.size());
                assertEquals("minecraft://events", resources.get(0).uri());
                assertEquals("application/json", resources.get(0).mimeType());

                McpSchema.ReadResourceResult read = client.readResource(new McpSchema.ReadResourceRequest("minecraft://events"));
                assertEquals(1, read.contents().size());
                assertEquals("application/json", read.contents().get(0).mimeType());
                String json = text(read);
                assertTrue(json.contains("\"text\":\"hello\",\"untrusted\":true"), json);
                assertTrue(json.endsWith("\"latest\":1,\"dropped\":0}"), json);
            }
        }
    }

    @Test
    void theResourceReturnsOnlyTheLatestEventsNotTheWholeBuffer() throws Exception {
        FakeGame game = new FakeGame();
        for (int i = 1; i <= 60; i++) {
            game.events.publish(EventType.CHAT, "Steve", "m" + i);
        }
        try (McpCore core = TestCores.full(game); McpSyncClient client = start(core)) {
            String json = text(client.readResource(new McpSchema.ReadResourceRequest("minecraft://events")));
            assertFalse(json.contains("\"text\":\"m10\""), json);
            assertTrue(json.contains("\"text\":\"m11\""), json);
            assertTrue(json.contains("\"text\":\"m60\""), json);
        }
    }

    @Test
    void aSubscribedClientIsToldWhenSomethingHappened() throws Exception {
        FakeGame game = new FakeGame();
        BlockingQueue<List<McpSchema.ResourceContents>> updates = new LinkedBlockingQueue<>();
        try (McpCore core = TestCores.full(game)) {
            core.start();
            McpSyncClient client = McpTestClients.client(core, updates::add);
            client.initialize();
            client.subscribeResource(new McpSchema.SubscribeRequest("minecraft://events"));

            game.events.publish(EventType.JOIN, "Alex", "");

            List<McpSchema.ResourceContents> update = updates.poll(10, TimeUnit.SECONDS);
            assertNotNull(update, "a subscribed client must be notified");
            assertTrue(((McpSchema.TextResourceContents) update.get(0)).text().contains("\"player\":\"Alex\""));

            client.unsubscribeResource(new McpSchema.UnsubscribeRequest("minecraft://events"));
            updates.clear();
            game.events.publish(EventType.JOIN, "Sam", "");
            assertNull(updates.poll(1, TimeUnit.SECONDS), "an unsubscribed client must not be notified");
            client.close();
        }
    }

    @Test
    void aResourceThatCannotBeSerializedIsReportedWithItsCause() {
        java.io.IOException cause = new java.io.IOException("no json today");
        io.modelcontextprotocol.json.McpJsonMapper broken = new io.modelcontextprotocol.json.McpJsonMapper() {
            @Override
            public <T> T readValue(String content, Class<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T readValue(byte[] content, Class<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T readValue(String content, io.modelcontextprotocol.json.TypeRef<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T readValue(byte[] content, io.modelcontextprotocol.json.TypeRef<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T convertValue(Object from, Class<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T convertValue(Object from, io.modelcontextprotocol.json.TypeRef<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String writeValueAsString(Object value) throws java.io.IOException {
                throw cause;
            }

            @Override
            public byte[] writeValueAsBytes(Object value) throws java.io.IOException {
                throw cause;
            }
        };

        var spec = EventResource.specification(new FakeGame(), broken);

        java.io.UncheckedIOException thrown = assertThrows(java.io.UncheckedIOException.class,
                () -> spec.readHandler().apply(null, new McpSchema.ReadResourceRequest("minecraft://events")));
        assertSame(cause, thrown.getCause());
    }

    @Test
    void aCoreWithoutAnEventFeedOffersNoResources() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();
            try (McpSyncClient client = McpTestClients.client(core)) {
                assertNull(client.initialize().capabilities().resources());
            }
        }
    }

    @Test
    void closingTheCoreStopsListeningToTheFeed() throws Exception {
        CountingFeed feed = new CountingFeed(new EventBuffer(10));
        GameServices services = new GameServices(Side.SERVER, feed);
        McpCore core = new McpCore("127.0.0.1", 0, ToolCatalog.defaults().availableFor(services),
                new ToolDispatcher(McpConfig.defaults(), services, AuditLog.discarding()), TestCores.TOKEN,
                services.find(EventFeed.class));
        assertEquals(1, feed.listeners(), "the core listens to the feed while it runs");
        core.start();

        core.close();

        assertEquals(0, feed.listeners(), "and stops when it closes");
    }

    private static McpSyncClient start(McpCore core) throws Exception {
        core.start();
        McpSyncClient client = McpTestClients.client(core);
        client.initialize();
        return client;
    }
}
