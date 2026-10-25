package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.LogBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;

/** Copies the game's log lines into a {@link LogBuffer} for {@code read_log}. */
final class FabricLogAppender extends AbstractAppender {

    private final LogBuffer buffer;

    private FabricLogAppender(LogBuffer buffer) {
        super("minecraft-mcp-log", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        this.buffer = buffer;
    }

    /** Starts feeding {@code buffer} from the root logger; call {@link #detach()} to stop. */
    static FabricLogAppender attach(LogBuffer buffer) {
        FabricLogAppender appender = new FabricLogAppender(buffer);
        appender.start();
        ((Logger) LogManager.getRootLogger()).addAppender(appender);
        return appender;
    }

    void detach() {
        ((Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent event) {
        buffer.add("[" + event.getLevel() + "] " + event.getMessage().getFormattedMessage());
    }
}
