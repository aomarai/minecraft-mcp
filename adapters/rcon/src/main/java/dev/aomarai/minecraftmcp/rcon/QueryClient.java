package dev.aomarai.minecraftmcp.rcon;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/**
 * Asks a game server who is online, over the Query protocol: a small UDP exchange that needs
 * {@code enable-query=true} and sends no password. It reports what the server says about itself (name,
 * version, map, player count) and the names of the players, and it is read-only.
 *
 * <p>The exchange is a handshake that returns a challenge number, then a "full stat" request carrying it.
 * Everything the server sends is untrusted text: names and the server's own description are shown to
 * the model, never obeyed.</p>
 */
public final class QueryClient {

    /** What a server reports about itself. */
    public record QueryInfo(String hostname, String version, String map, int playersOnline, int maxPlayers,
                            List<String> players) {
        public QueryInfo {
            players = List.copyOf(players);
        }
    }

    private static final int TYPE_STAT = 0;
    private static final int TYPE_HANDSHAKE = 9;
    /** Type and session id. */
    private static final int HEADER = 5;
    /** "splitnum" and its marker bytes, then the key/value section starts. */
    private static final int STAT_PADDING = 11;
    /** "\u0001player_" and its terminator, between the key/value section and the player names. */
    private static final int PLAYER_PADDING = 10;
    private static final int MAX_REPLY = 16384;
    /** The protocol only uses the low nibble of each session id byte. */
    private static final int SESSION_MASK = 0x0F0F0F0F;

    private final String host;
    private final int port;
    private final IntSupplier sessionIds;

    public QueryClient(String host, int port) {
        this(host, port, () -> ThreadLocalRandom.current().nextInt());
    }

    QueryClient(String host, int port, IntSupplier sessionIds) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.host = host;
        this.port = port;
        this.sessionIds = sessionIds;
    }

    /**
     * Blocks for up to {@code timeout} per round trip.
     *
     * @throws IOException if the server does not answer, or answers with something that is not a Query reply
     */
    public QueryInfo query(Duration timeout) throws IOException {
        int session = sessionIds.getAsInt() & SESSION_MASK;
        int millis = (int) Math.max(1, Math.min(timeout.toMillis(), Integer.MAX_VALUE));
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(millis);
            socket.connect(InetAddress.getByName(host), port);

            send(socket, ByteBuffer.allocate(7).put((byte) 0xFE).put((byte) 0xFD).put((byte) TYPE_HANDSHAKE).putInt(session));
            int challenge = challenge(receive(socket, TYPE_HANDSHAKE, session));

            send(socket, ByteBuffer.allocate(15).put((byte) 0xFE).put((byte) 0xFD).put((byte) TYPE_STAT)
                    .putInt(session).putInt(challenge).putInt(0));
            return parse(receive(socket, TYPE_STAT, session));
        } catch (SocketTimeoutException e) {
            throw new IOException("the server did not answer the query within " + millis + " ms (is enable-query on?)", e);
        }
    }

    private static void send(DatagramSocket socket, ByteBuffer request) throws IOException {
        socket.send(new DatagramPacket(request.array(), request.array().length));
    }

    /** The reply's content after the type and session id. */
    private static byte[] receive(DatagramSocket socket, int expectedType, int session) throws IOException {
        byte[] buffer = new byte[MAX_REPLY];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        if (packet.getLength() < HEADER || buffer[0] != expectedType || ByteBuffer.wrap(buffer, 1, 4).getInt() != session) {
            throw new IOException("the server's reply is not a Query reply to this request");
        }
        return java.util.Arrays.copyOfRange(buffer, HEADER, packet.getLength());
    }

    /** The challenge is a number written as text and ended with a zero byte. */
    private static int challenge(byte[] content) throws IOException {
        String text = new Cursor(content).next();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IOException("the server's challenge is not a number");
        }
    }

    private static QueryInfo parse(byte[] content) throws IOException {
        Cursor cursor = new Cursor(content);
        cursor.skip(STAT_PADDING);
        Map<String, String> values = new HashMap<>();
        for (String key = cursor.next(); !key.isEmpty(); key = cursor.next()) {
            values.put(key, cursor.next());
        }
        cursor.skip(PLAYER_PADDING);
        List<String> players = new ArrayList<>();
        for (String name = cursor.next(); !name.isEmpty(); name = cursor.next()) {
            players.add(name);
        }
        return new QueryInfo(values.getOrDefault("hostname", ""), values.getOrDefault("version", ""),
                values.getOrDefault("map", ""), number(values, "numplayers"), number(values, "maxplayers"), players);
    }

    private static int number(Map<String, String> values, String key) throws IOException {
        try {
            return Integer.parseInt(values.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            throw new IOException("the server's " + key + " is not a number");
        }
    }

    /** Reads zero-terminated strings from a reply, failing cleanly when it ends too soon. */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        /** Moves past bytes that carry nothing; running off the end shows up at the next {@link #next()}. */
        void skip(int count) {
            position += count;
        }

        String next() throws IOException {
            int end = position;
            while (end < data.length && data[end] != 0) {
                end++;
            }
            if (end >= data.length) {
                throw new IOException("the Query reply is cut short");
            }
            String text = new String(data, position, end - position, StandardCharsets.UTF_8);
            position = end + 1;
            return text;
        }
    }
}
