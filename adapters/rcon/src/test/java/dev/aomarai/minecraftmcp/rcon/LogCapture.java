package dev.aomarai.minecraftmcp.rcon;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Records everything logged while open, so tests can assert on (and against) log output. */
final class LogCapture implements AutoCloseable {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger root = Logger.getLogger("");
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord entry) {
            Object[] params = entry.getParameters();
            lines.add(entry.getMessage() + " " + (params == null ? "" : List.of(params)));
        }

        @Override
        public void flush() {
            // Nothing is buffered: publish() stores each record at once.
        }

        @Override
        public void close() {
            // Nothing to release; the capture removes the handler itself.
        }
    };

    LogCapture() {
        root.addHandler(handler);
    }

    /** One line per log record: the message followed by its parameters. */
    List<String> lines() {
        return lines;
    }

    @Override
    public void close() {
        root.removeHandler(handler);
    }
}
