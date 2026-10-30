package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.CommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RconClientTest {

    private static final Duration PATIENT = Duration.ofSeconds(10);

    private FakeRconServer server;
    private RconClient client;

    @BeforeEach
    void start() throws IOException {
        server = new FakeRconServer();
        client = new RconClient(config(server.port(), FakeRconServer.PASSWORD));
    }

    @AfterEach
    void stop() throws IOException {
        client.close();
        server.close();
    }

    private static RconConfig config(int port, String password) {
        return new RconConfig("127.0.0.1", port, password, false);
    }

    private CommandResult run(String command) throws Exception {
        return client.runCommand(command, PATIENT).get(20, TimeUnit.SECONDS);
    }

    @Test
    void aCommandIsRunAndItsReplyReturned() throws Exception {
        CommandResult result = run("list");

        assertTrue(result.success());
        assertEquals("ran list", result.output());
        assertEquals(List.of(), result.feedback());
    }

    @Test
    void theServerSeesTheAuthenticationThenTheCommandThenAnEndMarker() throws Exception {
        run("say hello");

        assertEquals(List.of(
                new RconPacket(1, RconPacket.AUTH, FakeRconServer.PASSWORD),
                new RconPacket(2, RconPacket.COMMAND, "say hello"),
                new RconPacket(3, RconPacket.RESPONSE, "")), server.received);
    }

    @Test
    void theConnectionIsReusedForTheNextCommand() throws Exception {
        run("one");
        CommandResult second = run("two");

        assertEquals("ran two", second.output());
        assertEquals(1, server.connections.get());
        assertEquals(1, server.received.stream().filter(p -> p.type() == RconPacket.AUTH).count());
    }

    @Test
    void aReplyThatTheServerSplitsIntoSeveralPacketsIsJoinedInOrder() throws Exception {
        server.replies = command -> List.of("part one, ", "part two, ", "part three");

        assertEquals("part one, part two, part three", run("help").output());
    }

    @Test
    void aReplyOfNothingIsAnEmptyReply() throws Exception {
        server.replies = command -> List.of();

        CommandResult result = run("tick");

        assertTrue(result.success());
        assertEquals("", result.output());
    }

    @Test
    void packetsThatAreNotTheCommandsReplyAreIgnored() throws Exception {
        server.noiseBeforeReply = true;
        server.replies = command -> List.of("answer");

        assertEquals("answer", run("list").output());
    }

    @Test
    void aWrongPasswordIsReportedWithoutRevealingAnyPassword() throws Exception {
        try (RconClient wrong = new RconClient(config(server.port(), "not the password"))) {
            CommandResult result = wrong.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertEquals(List.of("RCON authentication failed: the server did not accept rcon.password"), result.feedback());
            assertFalse(result.feedback().toString().contains("not the password"));
            assertFalse(result.feedback().toString().contains(FakeRconServer.PASSWORD));
            assertTrue(server.received.stream().noneMatch(p -> p.type() == RconPacket.COMMAND));
        }
    }

    @Test
    void afterAWrongPasswordTheNextCommandStartsOverWithANewConnection() throws Exception {
        try (RconClient wrong = new RconClient(config(server.port(), "not the password"))) {
            wrong.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);
            wrong.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);

            assertEquals(2, server.connections.get());
        }
    }

    @Test
    void anEmptyPacketBeforeTheAuthenticationAnswerIsSkipped() throws Exception {
        server.emptyPacketBeforeAuthAnswer = true;

        assertEquals("ran list", run("list").output());
    }

    @Test
    void aWrongPasswordIsStillNoticedWhenTheServerSendsAnEmptyPacketFirst() throws Exception {
        server.emptyPacketBeforeAuthAnswer = true;
        try (RconClient wrong = new RconClient(config(server.port(), "not the password"))) {
            CommandResult result = wrong.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertEquals(List.of("RCON authentication failed: the server did not accept rcon.password"), result.feedback());
        }
    }

    @Test
    void aConnectionThatFailedIsClosedAndNeverReused() throws Exception {
        server.ignoreCommands = true;
        client.runCommand("list", Duration.ofMillis(200)).get(20, TimeUnit.SECONDS);

        assertTrue(server.awaitDisconnections(1), "the client closes a connection that timed out");
    }

    @Test
    void aFailedLoginClosesTheConnection() throws Exception {
        try (RconClient wrong = new RconClient(config(server.port(), "not the password"))) {
            wrong.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);

            assertTrue(server.awaitDisconnections(1));
        }
    }

    @Test
    void theTimeoutOfTheLatestCommandAppliesEvenOnAReusedConnection() throws Exception {
        run("first");
        server.ignoreCommands = true;

        long started = System.nanoTime();
        CommandResult result = client.runCommand("second", Duration.ofMillis(200)).get(20, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "it must not wait for the earlier, longer timeout");
    }

    @Test
    void aSocketThatCannotBeClosedDoesNotHideTheRealResult() throws Exception {
        server.ignoreCommands = true;
        try (RconClient stubborn = new RconClient(config(server.port(), FakeRconServer.PASSWORD), () -> new Socket() {
            @Override
            public synchronized void close() throws IOException {
                super.close();
                throw new IOException("close failed");
            }
        })) {
            CommandResult result = stubborn.runCommand("list", Duration.ofMillis(200)).get(20, TimeUnit.SECONDS);

            assertEquals(List.of("RCON did not answer within 200 ms; the command may still have taken effect"), result.feedback());
            assertTrue(server.awaitDisconnections(1));
        }
    }

    @Test
    void aServerThatNeverAnswersTheLoginTimesOut() throws Exception {
        server.ignoreAuth = true;

        CommandResult result = client.runCommand("list", Duration.ofMillis(300)).get(20, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertEquals(List.of("RCON did not answer within 300 ms; the command may still have taken effect"), result.feedback());
    }

    @Test
    void aServerThatNeverAnswersACommandTimesOutAndTheNextCommandReconnects() throws Exception {
        server.ignoreCommands = true;

        CommandResult timedOut = client.runCommand("list", Duration.ofMillis(300)).get(20, TimeUnit.SECONDS);

        assertFalse(timedOut.success());
        assertEquals(List.of("RCON did not answer within 300 ms; the command may still have taken effect"), timedOut.feedback());

        server.ignoreCommands = false;
        assertEquals("ran again", run("again").output());
        assertEquals(2, server.connections.get(), "the connection that timed out is not reused");
    }

    @Test
    void aZeroTimeoutIsOneMillisecondNotForever() throws Exception {
        server.ignoreCommands = true;

        CommandResult result = client.runCommand("list", Duration.ZERO).get(20, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.feedback().get(0).startsWith("RCON did not answer within 1 ms"), result.feedback().toString());
    }

    @Test
    void anEnormousTimeoutIsClampedNotWrappedAround() throws Exception {
        CommandResult result = client.runCommand("list", Duration.ofDays(1_000_000)).get(20, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertEquals("ran list", result.output());
    }

    @Test
    void aServerThatHangsUpMidCommandIsAnErrorAndTheNextCommandReconnects() throws Exception {
        server.hangUpAfterCommand = true;

        CommandResult dropped = run("stop");

        assertFalse(dropped.success());
        assertTrue(dropped.feedback().get(0).startsWith("RCON failed: java.io.EOFException"), dropped.feedback().toString());

        server.hangUpAfterCommand = false;
        assertEquals("ran list", run("list").output());
        assertEquals(2, server.connections.get());
    }

    @Test
    void aReplyOverTheByteCapIsCutOffAndTheNextCommandUsesAFreshConnection() throws Exception {
        server.floodPauseMillis = 0;

        CommandResult flooded = run("help");

        assertFalse(flooded.success());
        assertEquals("the RCON reply is larger than " + RconClient.MAX_REPLY_BYTES
                + " bytes; the connection was closed and the command may still have taken effect", flooded.feedback().get(0));
        assertTrue(server.awaitDisconnections(1));

        server.floodPauseMillis = -1;
        assertEquals("ran list", run("list").output());
        assertEquals(2, server.connections.get());
    }

    @Test
    void aReplyThatTrickledInForLongerThanTheTimeoutEndsAtTheTimeoutNotAtTheLastPacket() throws Exception {
        server.floodPauseMillis = 40;

        long start = System.nanoTime();
        CommandResult dripped = client.runCommand("help", Duration.ofMillis(300)).get(20, TimeUnit.SECONDS);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertFalse(dripped.success());
        assertEquals("RCON did not answer within 300 ms; the command may still have taken effect", dripped.feedback().get(0));
        assertTrue(elapsed >= 300 && elapsed < 2_000, "elapsed " + elapsed);
        assertTrue(server.awaitDisconnections(1));

        server.floodPauseMillis = -1;
        assertEquals("ran list", run("list").output());
        assertEquals(2, server.connections.get());
    }

    /**
     * A clock that says 0 when the round trip starts and {@code later} for every look after that. Each later look
     * first waits a moment of real time, so the fake server has answered by then: with a millisecond left, the read
     * is only sure to see the reply if it is already there, and CI cannot promise that within one millisecond.
     */
    private RconClient clientWhoseClockSays(long laterMillis) {
        long[] now = {0};
        java.util.function.LongSupplier clock = () -> {
            long value = now[0];
            if (value != 0) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            now[0] = laterMillis * 1_000_000L;
            return value;
        };
        return new RconClient(config(server.port(), FakeRconServer.PASSWORD), Socket::new, clock);
    }

    @Test
    void aReplyIsReadWhileAMillisecondIsLeftOfTheTimeout() throws Exception {
        try (RconClient timed = clientWhoseClockSays(99)) {
            CommandResult result = timed.runCommand("list", Duration.ofMillis(100)).get(20, TimeUnit.SECONDS);

            assertEquals("ran list", result.output());
        }
    }

    @Test
    void noReplyIsReadOnceTheTimeoutHasRunOut() throws Exception {
        try (RconClient timed = clientWhoseClockSays(100)) {
            CommandResult result = timed.runCommand("list", Duration.ofMillis(100)).get(20, TimeUnit.SECONDS);

            assertEquals("RCON did not answer within 100 ms; the command may still have taken effect", result.feedback().get(0));
        }
    }

    @Test
    void aReplyOfExactlyTheByteCapIsAcceptedAndOneByteMoreIsNot() throws Exception {
        String chunk = "x".repeat(4000);
        int full = RconClient.MAX_REPLY_BYTES / 4000;
        String rest = "y".repeat(RconClient.MAX_REPLY_BYTES - full * 4000);
        java.util.List<String> packets = new java.util.ArrayList<>(java.util.Collections.nCopies(full, chunk));
        packets.add(rest);
        server.replies = command -> packets;

        assertTrue(run("help").success());

        packets.add("z");
        assertFalse(run("help").success());
    }

    @Test
    void aServerThatIsNotListeningIsAnError() throws Exception {
        try (Socket reserved = refusingPort();
             RconClient nobody = new RconClient(config(reserved.getLocalPort(), FakeRconServer.PASSWORD))) {
            CommandResult result = nobody.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertTrue(result.feedback().get(0).startsWith("RCON failed: java.net.ConnectException"), result.feedback().toString());
        }
    }

    @Test
    void aCommandThatDoesNotFitInOnePacketIsRefusedBeforeAnythingIsSent() throws Exception {
        CommandResult result = run("x".repeat(1447));

        assertFalse(result.success());
        assertEquals(List.of("the command is 1447 bytes; RCON accepts at most 1446"), result.feedback());
        assertEquals(0, server.connections.get());
    }

    @Test
    void theLimitIsInBytesNotCharacters() throws Exception {
        // 724 two-byte characters are 1448 bytes.
        CommandResult result = run("é".repeat(724));

        assertEquals(List.of("the command is 1448 bytes; RCON accepts at most 1446"), result.feedback());
    }

    @Test
    void aCommandOfExactlyTheLimitIsSent() throws Exception {
        String command = "x".repeat(1446);

        assertEquals("ran " + command, run(command).output());
    }

    @Test
    void aCommandCancelledBeforeItsTurnIsNeverSent() throws Exception {
        server.holdCommands = new CountDownLatch(1);
        CompletableFuture<CommandResult> first = client.runCommand("first", PATIENT);
        assertTrue(server.commandArrived.await(10, TimeUnit.SECONDS));

        CompletableFuture<CommandResult> second = client.runCommand("second", PATIENT);
        second.cancel(false);
        server.holdCommands.countDown();

        assertEquals("ran first", first.get(20, TimeUnit.SECONDS).output());
        assertEquals("ran third", run("third").output());
        assertTrue(server.received.stream().noneMatch(p -> p.payload().equals("second")));
    }

    @Test
    void closingTheClientClosesTheConnection() throws Exception {
        run("list");
        client.close();

        assertTrue(server.awaitDisconnections(1));

        // The fake server's handler ends when its client leaves; a later command is refused.
        CommandResult after = client.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);
        assertFalse(after.success());
        assertEquals(List.of("the RCON client is closed"), after.feedback());
    }

    @Test
    void closingTwiceAndClosingAClientThatNeverConnectedAreFine() {
        RconClient idle = new RconClient(config(server.port(), FakeRconServer.PASSWORD));

        assertDoesNotThrow(idle::close);
        assertDoesNotThrow(idle::close);
    }

    @Test
    void theWorkerThreadIsADaemonSoItCannotKeepTheJvmAlive() throws Exception {
        RconClient probe = new RconClient(config(server.port(), FakeRconServer.PASSWORD));
        probe.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);

        Thread worker = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("minecraft-mcp-rcon")).findFirst().orElseThrow();
        assertTrue(worker.isDaemon());
        probe.close();
    }

    @Test
    void aMalformedReplyIsAnError() throws Exception {
        // A server that answers with something that is not RCON at all.
        try (ServerSocket rogue = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread thread = new Thread(() -> {
                try (var socket = rogue.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    socket.getOutputStream().write(new byte[]{1, 0, 0, 0, 7, 7, 7, 7});
                } catch (IOException ignored) {
                    // The client gave up.
                }
            });
            thread.setDaemon(true);
            thread.start();
            try (RconClient victim = new RconClient(config(rogue.getLocalPort(), FakeRconServer.PASSWORD))) {
                CommandResult result = victim.runCommand("list", PATIENT).get(20, TimeUnit.SECONDS);

                assertFalse(result.success());
                assertTrue(result.feedback().get(0).startsWith("RCON failed: java.io.IOException: malformed RCON packet"),
                        result.feedback().toString());
            }
        }
    }

    @Test
    void theFutureNeverCompletesExceptionally() throws Exception {
        // Every failure above is a CommandResult; this pins that an unreachable server does not throw.
        try (Socket reserved = refusingPort();
             RconClient nobody = new RconClient(config(reserved.getLocalPort(), FakeRconServer.PASSWORD))) {
            CompletableFuture<CommandResult> future = nobody.runCommand("list", PATIENT);

            CommandResult result = assertDoesNotThrow(() -> future.get(20, TimeUnit.SECONDS),
                    "the future must complete normally");
            assertFalse(result.success());
        }
    }

    /**
     * A loopback port that refuses every connection and that nothing else can take while the test holds it: bound but
     * not listening. (The port of a closed server socket can be handed to another listener, which then answers.)
     */
    private static Socket refusingPort() throws IOException {
        Socket reserved = new Socket();
        reserved.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return reserved;
    }
}
