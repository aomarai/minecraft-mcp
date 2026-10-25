package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * Recent lines of the server log. The log holds chat and player names, so the text is untrusted
 * and the core redacts it before it leaves ({@link LogRedaction}).
 */
public interface LogReader extends GamePort {

    int MAX_LINES = 200;

    /** The last {@code lines} lines, oldest first. Empty if there is no log yet. */
    List<String> tail(int lines) throws java.io.IOException;
}
