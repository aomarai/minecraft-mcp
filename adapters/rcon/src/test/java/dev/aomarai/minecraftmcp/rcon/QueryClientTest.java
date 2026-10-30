package dev.aomarai.minecraftmcp.rcon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryClientTest {

    private static final Duration PATIENT = Duration.ofSeconds(10);
    /** The session id the tests make the client use; the protocol keeps only the low nibble of each byte. */
    private static final int RAW_SESSION = 0xA1B2C3D0;
    private static final int SESSION = 0x01020300;

    private FakeQueryServer server;
    private QueryClient client;

    @BeforeEach
    void start() throws IOException {
        server = new FakeQueryServer();
        client = new QueryClient("127.0.0.1", server.port(), () -> RAW_SESSION);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void theServerIsAskedForItsFullStatAndEverythingItReportsIsReturned() throws IOException {
        QueryClient.QueryInfo info = client.query(PATIENT);

        assertEquals("A Minecraft Server", info.hostname());
        assertEquals("1.21.1", info.version());
        assertEquals("world", info.map());
        assertEquals(2, info.playersOnline());
        assertEquals(20, info.maxPlayers());
        assertEquals(List.of("Steve", "Alex"), info.players());
    }

    @Test
    void theRequestsAreExactlyWhatTheProtocolSays() throws IOException {
        client.query(PATIENT);

        assertEquals(2, server.received.size());
        assertArrayEquals(ByteBuffer.allocate(7).put((byte) 0xFE).put((byte) 0xFD).put((byte) 9).putInt(SESSION).array(),
                server.received.get(0));
        assertArrayEquals(ByteBuffer.allocate(15).put((byte) 0xFE).put((byte) 0xFD).put((byte) 0).putInt(SESSION)
                .putInt(FakeQueryServer.CHALLENGE).putInt(0).array(), server.received.get(1));
    }

    @Test
    void aNegativeChallengeIsSentBackAsItWas() throws IOException {
        server.challengeText = "-12345";

        client.query(PATIENT);

        assertEquals(-12345, ByteBuffer.wrap(server.received.get(1), 7, 4).getInt());
    }

    @Test
    void anEmptyServerHasNoPlayersAndAMissingFieldIsEmpty() throws IOException {
        server.values = List.of("hostname", "Quiet", "numplayers", "0", "maxplayers", "5");
        server.players = List.of();

        QueryClient.QueryInfo info = client.query(PATIENT);

        assertEquals("Quiet", info.hostname());
        assertEquals("", info.version());
        assertEquals("", info.map());
        assertEquals(List.of(), info.players());
    }

    @Test
    void aServerThatDoesNotReportCountsHasZero() throws IOException {
        server.values = List.of("hostname", "Plain");

        QueryClient.QueryInfo info = client.query(PATIENT);

        assertEquals(0, info.playersOnline());
        assertEquals(0, info.maxPlayers());
    }

    @Test
    void namesAreReadAsUtf8() throws IOException {
        server.players = List.of("Ünïcode");

        assertEquals(List.of("Ünïcode"), client.query(PATIENT).players());
    }

    @Test
    void theReturnedInfoCannotBeChangedByTheCaller() throws IOException {
        QueryClient.QueryInfo info = client.query(PATIENT);

        var players2 = info.players();
        assertThrows(UnsupportedOperationException.class, () -> players2.clear());
    }

    @Test
    void aSilentServerIsAnErrorThatSuggestsEnablingQuery() {
        server.silent = true;

        var arg = Duration.ofMillis(300);
        IOException failure = assertThrows(IOException.class, () -> client.query(arg));

        assertEquals("the server did not answer the query within 300 ms (is enable-query on?)", failure.getMessage());
    }

    @Test
    void aZeroTimeoutIsOneMillisecondNotForever() {
        server.silent = true;

        IOException failure = assertThrows(IOException.class, () -> client.query(Duration.ZERO));

        assertTrue(failure.getMessage().contains("within 1 ms"), failure.getMessage());
    }

    @Test
    void anEnormousTimeoutIsClampedNotWrappedAround() throws IOException {
        assertEquals("world", client.query(Duration.ofDays(1_000_000)).map());
    }

    @Test
    void aReplyOfTheWrongTypeIsRejected() {
        server.replyType = 7;

        assertEquals("the server's reply is not a Query reply to this request",
                assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aReplyForAnotherSessionIsRejected() {
        server.sessionOffset = 1;

        assertEquals("the server's reply is not a Query reply to this request",
                assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aReplyThatIsTooShortToHaveAHeaderIsRejected() {
        // Right type and the first three bytes of the right session id, but only 4 bytes: a header is 5.
        server.rawStatReply = new byte[]{0, 1, 2, 3};

        assertEquals("the server's reply is not a Query reply to this request",
                assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aReplyOfJustAHeaderIsAQueryReplyWithNothingInIt() {
        server.rawStatReply = new byte[]{0, 1, 2, 3, 0};

        assertEquals("the Query reply is cut short", assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aChallengeThatIsNotANumberIsRejected() {
        server.challengeText = "twelve";

        assertEquals("the server's challenge is not a number",
                assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aChallengeWithNoTerminatorIsRejected() {
        // Type 9, the session id, and "123" without the zero byte that ends the text.
        server.rawHandshakeReply = new byte[]{9, 1, 2, 3, 0, '1', '2', '3'};

        assertEquals("the Query reply is cut short", assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aCountThatIsNotANumberIsRejected() {
        server.values = List.of("numplayers", "many");

        assertEquals("the server's numplayers is not a number",
                assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aMaximumThatIsNotANumberIsRejected() {
        server.values = List.of("maxplayers", "lots");

        assertEquals("the server's maxplayers is not a number",
                assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aReplyThatEndsInTheMiddleIsRejected() {
        ByteBuffer reply = ByteBuffer.allocate(40);
        reply.put((byte) 0).putInt(SESSION).put(new byte[11]).put("hostname".getBytes());
        server.rawStatReply = java.util.Arrays.copyOf(reply.array(), reply.position());

        assertEquals("the Query reply is cut short", assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aReplyWithNoRoomForThePlayerSectionIsRejected() {
        ByteBuffer reply = ByteBuffer.allocate(60);
        reply.put((byte) 0).putInt(SESSION).put(new byte[11]).put((byte) 0);
        server.rawStatReply = java.util.Arrays.copyOf(reply.array(), reply.position());

        assertEquals("the Query reply is cut short", assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void aNameListThatNeverEndsIsRejected() {
        ByteBuffer reply = ByteBuffer.allocate(80);
        reply.put((byte) 0).putInt(SESSION).put(new byte[11]).put((byte) 0).put(new byte[10]).put("Steve".getBytes());
        server.rawStatReply = java.util.Arrays.copyOf(reply.array(), reply.position());

        assertEquals("the Query reply is cut short", assertThrows(IOException.class, () -> client.query(PATIENT)).getMessage());
    }

    @Test
    void theHostAndPortAreChecked() {
        assertEquals("host must not be empty", assertThrows(IllegalArgumentException.class,
                () -> new QueryClient(" ", 25565)).getMessage());
        assertEquals("host must not be empty", assertThrows(IllegalArgumentException.class,
                () -> new QueryClient(null, 25565)).getMessage());
        assertEquals("port must be between 1 and 65535", assertThrows(IllegalArgumentException.class,
                () -> new QueryClient("127.0.0.1", 0)).getMessage());
        assertEquals("port must be between 1 and 65535", assertThrows(IllegalArgumentException.class,
                () -> new QueryClient("127.0.0.1", 65536)).getMessage());
        assertDoesNotThrow(() -> new QueryClient("127.0.0.1", 1));
        assertDoesNotThrow(() -> new QueryClient("127.0.0.1", 65535));
    }

    @Test
    void thePublicConstructorPicksItsOwnSessionIds() throws IOException {
        QueryClient random = new QueryClient("127.0.0.1", server.port());

        for (int i = 0; i < 20; i++) {
            assertEquals(2, random.query(PATIENT).playersOnline());
        }

        // Every query asks with a session id; random ones are not all the same (and not all zero).
        java.util.Set<Integer> sessions = new java.util.HashSet<>();
        for (int i = 0; i < server.received.size(); i += 2) {
            sessions.add(ByteBuffer.wrap(server.received.get(i), 3, 4).getInt());
        }
        assertTrue(sessions.size() > 1, sessions.toString());
        assertFalse(sessions.contains(0) && sessions.size() == 1);
    }

    @Test
    void aPortWithNothingListeningIsAnErrorNotAHang() throws IOException {
        int closed;
        try (DatagramSocket probe = new DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        QueryClient nobody = new QueryClient("127.0.0.1", closed);

        var arg2 = Duration.ofMillis(500);
        assertThrows(IOException.class, () -> nobody.query(arg2));
    }
}
