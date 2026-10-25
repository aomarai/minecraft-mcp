package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * Recent server log lines, oldest first. Log text includes chat and player names, so it is untrusted:
 * it can be shown to the model but never authorizes anything. Each line is cut to
 * {@value #MAX_LINE_LENGTH} characters.
 *
 * @param truncated whether the log held more lines than the limit allowed
 */
public record LogLines(List<String> lines, boolean truncated) {

    public static final int MAX_LINE_LENGTH = 500;

    public LogLines {
        lines = lines.stream()
                .map(line -> line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) : line)
                .toList();
    }
}
