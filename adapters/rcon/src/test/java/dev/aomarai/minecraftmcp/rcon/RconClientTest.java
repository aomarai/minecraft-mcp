package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.CommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    void aServerThatIsNotListeningIsAnError() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        try (RconClient nobody = new RconClient(config(closed, FakeRconServer.PASSWORD))) {
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
        server.close();
        CompletableFuture<CommandResult> future = client.runCommand("list", PATIENT);

        try {
            assertFalse(future.get(20, TimeUnit.SECONDS).success());
        } catch (ExecutionException | TimeoutException e) {
            fail("the future must complete normally: " + e);
        }
    }
}
