package dev.aomarai.minecraftmcp.core;

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
        public void publish(LogRecord record) {
            Object[] params = record.getParameters();
            lines.add(record.getMessage() + " " + (params == null ? "" : List.of(params)));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
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
