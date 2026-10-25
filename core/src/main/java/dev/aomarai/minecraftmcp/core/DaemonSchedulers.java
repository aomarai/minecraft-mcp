package dev.aomarai.minecraftmcp.core;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ThreadFactory;

/**
 * Makes every thread that Reactor starts for this process a daemon thread.
 *
 * <p>The MCP SDK's HTTP transport starts its idle-session sweeper on {@code Schedulers.newSingle},
 * whose thread is not a daemon, and closing the transport disposes the sweeper but not that
 * scheduler. The thread then outlives {@link McpCore#close()} and keeps the JVM running after the
 * game server has stopped. Daemon threads cannot block JVM exit, and the sweeper keeps working
 * while the server runs.</p>
 *
 * <p>This is global to the Reactor copy that loads this class. The Fabric jar relocates Reactor, so
 * it affects only this mod's copy, never another mod's.</p>
 */
final class DaemonSchedulers {

    private static final Schedulers.Factory DAEMON = new Schedulers.Factory() {
        @Override
        public Scheduler newBoundedElastic(int threadCap, int taskCap, ThreadFactory threads, int ttlSeconds) {
            return Schedulers.Factory.super.newBoundedElastic(threadCap, taskCap, daemon(threads), ttlSeconds);
        }

        @Override
        public Scheduler newParallel(int parallelism, ThreadFactory threads) {
            return Schedulers.Factory.super.newParallel(parallelism, daemon(threads));
        }

        @Override
        public Scheduler newSingle(ThreadFactory threads) {
            return Schedulers.Factory.super.newSingle(daemon(threads));
        }
    };

    private DaemonSchedulers() {
    }

    /** Idempotent. Call before anything creates a Reactor scheduler. */
    static void install() {
        Schedulers.setFactory(DAEMON);
    }

    private static ThreadFactory daemon(ThreadFactory delegate) {
        return runnable -> {
            Thread thread = delegate.newThread(runnable);
            thread.setDaemon(true);
            return thread;
        };
    }
}
