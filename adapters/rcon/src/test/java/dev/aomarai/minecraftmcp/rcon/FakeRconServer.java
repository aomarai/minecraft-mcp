package dev.aomarai.minecraftmcp.rcon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A server that speaks RCON on a loopback port, scripted by the test that started it. */
final class FakeRconServer implements AutoCloseable {

    static final String PASSWORD = "correct horse battery staple";

    final List<RconPacket> received = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    /** Connections that have ended, whoever ended them. */
    final AtomicInteger disconnections = new AtomicInteger();

    /** What the server answers to a command, in packets. */
    volatile Function<String, List<String>> replies = command -> List.of("ran " + command);
    /** A packet of the wrong type, sent before the authentication answer, as some servers do. */
    volatile boolean emptyPacketBeforeAuthAnswer;
    /** A packet with an id nobody asked about, sent before the reply. */
    volatile boolean noiseBeforeReply;
    volatile boolean ignoreAuth;
    volatile boolean ignoreCommands;
    volatile boolean hangUpAfterCommand;
    /** When set, a command is answered with packets that never stop coming, this many milliseconds apart (0: as fast as possible). */
    volatile long floodPauseMillis = -1;
    /** When set, a command waits here before it is answered. */
    volatile CountDownLatch holdCommands;
    /** Counted down when a command has arrived. */
    volatile CountDownLatch commandArrived = new CountDownLatch(1);

    private final ServerSocket server;
    private final Thread acceptor;

    FakeRconServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "fake-rcon-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Waits until this many connections have ended. */
    boolean awaitDisconnections(int count) throws InterruptedException {
        return Await.until(() -> disconnections.get() >= count, java.time.Duration.ofSeconds(10));
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        try {
            while (true) {
                Socket client = server.accept();
                connections.incrementAndGet();
                Thread handler = new Thread(() -> serve(client), "fake-rcon-client");
                handler.setDaemon(true);
                handler.start();
            }
        } catch (IOException closed) {
            // The test is over.
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            while (true) {
                RconPacket request = RconPacket.read(in);
                received.add(request);
                if (request.type() == RconPacket.AUTH) {
                    authenticate(request, out);
                } else if (request.type() == RconPacket.COMMAND) {
                    if (!command(request, out)) {
                        return;
                    }
                } else if (!ignoreCommands) {
                    out.write(new RconPacket(request.id(), RconPacket.RESPONSE, "Unknown request " + request.type()).encode());
                }
            }
        } catch (IOException | InterruptedException clientLeft) {
            // The client closed the connection, or the test is over.
        } finally {
            disconnections.incrementAndGet();
        }
    }

    private void authenticate(RconPacket request, OutputStream out) throws IOException {
        if (ignoreAuth) {
            return;
        }
        if (emptyPacketBeforeAuthAnswer) {
            out.write(new RconPacket(request.id(), RconPacket.RESPONSE, "").encode());
        }
        int id = request.payload().equals(PASSWORD) ? request.id() : RconPacket.AUTH_FAILED;
        out.write(new RconPacket(id, RconPacket.COMMAND, "").encode());
    }

    /** Returns false when the connection should be dropped. */
    private boolean command(RconPacket request, OutputStream out) throws IOException, InterruptedException {
        commandArrived.countDown();
        CountDownLatch hold = holdCommands;
        if (hold != null) {
            hold.await();
        }
        if (ignoreCommands) {
            return true;
        }
        if (hangUpAfterCommand) {
            return false;
        }
        if (noiseBeforeReply) {
            out.write(new RconPacket(99, RconPacket.RESPONSE, "noise").encode());
        }
        while (floodPauseMillis >= 0) {
            out.write(new RconPacket(request.id(), RconPacket.RESPONSE, "x".repeat(1000)).encode());
            // The pause is the point: a server that keeps sending, one packet every so often.
            Thread.sleep(floodPauseMillis); // NOSONAR java:S2925
        }
        for (String chunk : replies.apply(request.payload())) {
            out.write(new RconPacket(request.id(), RconPacket.RESPONSE, chunk).encode());
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
