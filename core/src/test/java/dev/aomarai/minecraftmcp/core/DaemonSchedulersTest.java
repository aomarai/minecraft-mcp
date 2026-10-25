package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DaemonSchedulersTest {

    private static boolean runsOnDaemonThread(Scheduler scheduler) throws InterruptedException {
        AtomicReference<Boolean> daemon = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        scheduler.schedule(() -> {
            daemon.set(Thread.currentThread().isDaemon());
            ran.countDown();
        });
        assertTrue(ran.await(5, TimeUnit.SECONDS), "the scheduler ran the task");
        scheduler.dispose();
        return daemon.get();
    }

    @Test
    void everyKindOfReactorSchedulerUsesDaemonThreadsOnceInstalled() throws Exception {
        DaemonSchedulers.install();
        DaemonSchedulers.install();

        assertTrue(runsOnDaemonThread(Schedulers.newSingle("test-single")), "single");
        assertTrue(runsOnDaemonThread(Schedulers.newParallel("test-parallel", 2)), "parallel");
        assertTrue(runsOnDaemonThread(Schedulers.newBoundedElastic(2, 10, "test-elastic")), "bounded elastic");
        assertTrue(runsOnDaemonThread(Schedulers.newBoundedElastic(2, 10, "test-elastic-explicit", 60, false)),
                "bounded elastic asked for non-daemon threads still gets daemon ones");
    }

    /**
     * The regression this class exists for: the SDK's session sweeper must not be able to keep the
     * JVM alive after the server is closed. The factory is reset first, so only {@code McpCore}
     * itself can have made the sweeper a daemon.
     */
    @Test
    void theSdkSessionSweeperDoesNotKeepTheJvmAlive() throws Exception {
        Schedulers.resetFactory();
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();

            List<Thread> sweepers = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("streamable-http-server-transport-session-sweeper"))
                    .toList();

            assertFalse(sweepers.isEmpty(), "the SDK starts its sweeper; if it stops doing so this guard is moot");
            sweepers.forEach(t -> assertTrue(t.isDaemon(), t.getName() + " must be a daemon thread"));
        }
    }
}
