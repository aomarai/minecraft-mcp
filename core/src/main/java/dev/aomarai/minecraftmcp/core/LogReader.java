package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only view of the most recent server log lines. */
public interface LogReader extends GamePort {

    CompletableFuture<LogLines> recentLines(LogQuery query);
}
