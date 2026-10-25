package dev.aomarai.minecraftmcp.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the end of a log file without loading the whole file: at most the last
 * {@value #MAX_BYTES} bytes are read, so a huge log cannot cost memory or time.
 */
public final class FileLogReader implements LogReader {

    static final int MAX_BYTES = 256 * 1024;

    private final Path file;

    public FileLogReader(Path file) {
        this.file = file;
    }

    @Override
    public List<String> tail(int lines) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (RandomAccessFile log = new RandomAccessFile(file.toFile(), "r")) {
            long length = log.length();
            long start = Math.max(0, length - MAX_BYTES);
            byte[] bytes = new byte[(int) (length - start)];
            log.seek(start);
            log.readFully(bytes);
            List<String> all = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            // When the read started mid-file, its first line is cut off, so it is dropped. (A full chunk
            // always holds at least one line, so there is something to drop.)
            List<String> whole = start > 0 ? all.subList(1, all.size()) : all;
            return whole.subList(Math.max(0, whole.size() - lines), whole.size());
        }
    }
}
