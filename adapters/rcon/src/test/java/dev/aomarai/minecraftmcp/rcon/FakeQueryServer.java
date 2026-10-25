package dev.aomarai.minecraftmcp.rcon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A server that speaks the Query protocol on a loopback UDP port, scripted by the test. */
final class FakeQueryServer implements AutoCloseable {

    static final int CHALLENGE = 9513307;

    /** Every datagram the server received, in order. */
    final List<byte[]> received = new CopyOnWriteArrayList<>();

    volatile boolean silent;
    /** The challenge text to send; null for the real one. */
    volatile String challengeText;
    /** The key/value pairs of the full stat, as one flat list: key, value, key, value... */
    volatile List<String> values = List.of("hostname", "A Minecraft Server", "version", "1.21.1", "map", "world",
            "numplayers", "2", "maxplayers", "20");
    volatile List<String> players = List.of("Steve", "Alex");
    /** Replaces the handshake reply when set. */
    volatile byte[] rawHandshakeReply;
    /** Replaces the full stat reply when set. */
    volatile byte[] rawStatReply;
    /** Added to the session id in replies, to simulate a reply meant for someone else. */
    volatile int sessionOffset;
    volatile int replyType = -1;

    private final DatagramSocket socket;
    private final Thread thread;

    FakeQueryServer() throws IOException {
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "fake-query");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    private void serve() {
        try {
            while (true) {
                byte[] buffer = new byte[256];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                byte[] data = java.util.Arrays.copyOf(buffer, request.getLength());
                received.add(data);
                if (silent) {
                    continue;
                }
                int type = data[2];
                int session = ByteBuffer.wrap(data, 3, 4).getInt();
                byte[] reply = type == 9 ? handshake(session) : stat(session);
                socket.send(new DatagramPacket(reply, reply.length, request.getSocketAddress()));
            }
        } catch (IOException closed) {
            // The test is over.
        }
    }

    private byte[] handshake(int session) {
        if (rawHandshakeReply != null) {
            return rawHandshakeReply;
        }
        String text = challengeText != null ? challengeText : Integer.toString(CHALLENGE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, replyType >= 0 ? replyType : 9, session);
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        return out.toByteArray();
    }

    private byte[] stat(int session) {
        if (rawStatReply != null) {
            return rawStatReply;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header(out, replyType >= 0 ? replyType : 0, session);
        out.writeBytes("splitnum".getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write(0x80);
        out.write(0);
        for (String text : values) {
            string(out, text);
        }
        out.write(0);
        out.write(1);
        out.writeBytes("player_".getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write(0);
        for (String name : players) {
            string(out, name);
        }
        out.write(0);
        return out.toByteArray();
    }

    private void header(ByteArrayOutputStream out, int type, int session) {
        out.write(type);
        out.writeBytes(ByteBuffer.allocate(4).putInt(session + sessionOffset).array());
    }

    static void string(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    @Override
    public void close() {
        socket.close();
    }
}
