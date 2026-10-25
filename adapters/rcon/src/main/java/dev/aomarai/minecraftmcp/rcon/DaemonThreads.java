package dev.aomarai.minecraftmcp.rcon;

import java.util.concurrent.ThreadFactory;

/** Threads that can never keep the JVM alive after the game server has stopped. */
final class DaemonThreads {

    private DaemonThreads() {
    }

    static ThreadFactory named(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
