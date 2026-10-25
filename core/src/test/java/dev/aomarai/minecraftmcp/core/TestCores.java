package dev.aomarai.minecraftmcp.core;

import java.time.Duration;

/** Builds cores wired to a fake game. */
final class TestCores {

    static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private TestCores() {
    }

    /** A core serving every default tool against {@code game}, with writes and admin disabled. */
    static McpCore full(FakeGame game) {
        McpConfig config = new McpConfig("127.0.0.1", 25599, false, false, 32768, Duration.ofSeconds(5));
        GameServices services = game.services();
        return new McpCore("127.0.0.1", 0, ToolCatalog.defaults().availableFor(services),
                new ToolDispatcher(config, services, AuditLog.discarding()), TOKEN);
    }
}
