package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.CommandResult;
import dev.aomarai.minecraftmcp.core.CommandRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runs commands on a game server over RCON, the server's remote console. Works with vanilla, Paper and
 * modded servers alike, because every one of them runs the command through its own dispatcher.
 *
 * <p>Commands run one at a time on a private daemon thread, so callers never block on a socket. The
 * connection is made on first use and reused. After any failure (a timeout, a dropped connection) it is
 * closed and the next command opens a fresh one, because a reply that arrives late could otherwise be
 * taken for the answer to the next command.</p>
 *
 * <p>RCON gives the caller the server console: the game itself will not refuse any command. The core's
 * policy, which runs before anything reaches this class, is the only barrier.</p>
 */
public final class RconClient implements CommandRunner, AutoCloseable {

    private static final int AUTH_ID = 1;
    private static final int COMMAND_ID = 2;
    /** An empty request of a type the server does not know is answered right after the command's own reply, so it marks the end of a multi-packet reply. */
    private static final int END_MARKER_ID = 3;

    /** The most reply bytes one command may bring. A server that sends more is cut off, not listened to. */
    static final int MAX_REPLY_BYTES = 64 * 1024;

    private final RconConfig config;
    private final Supplier<Socket> sockets;
    private final LongSupplier nanos;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(DaemonThreads.named("minecraft-mcp-rcon"));
    /** The logged-in connection, if any. Only the worker thread opens and uses it; close() may close it from another thread. */
    private volatile Socket connection;

    public RconClient(RconConfig config) {
        this(config, Socket::new, System::nanoTime);
    }

    RconClient(RconConfig config, Supplier<Socket> sockets) {
        this(config, sockets, System::nanoTime);
    }

    RconClient(RconConfig config, Supplier<Socket> sockets, LongSupplier nanos) {
        this.config = config;
        this.sockets = sockets;
        this.nanos = nanos;
    }

    @Override
    public CompletableFuture<CommandResult> runCommand(String command, Duration timeout) {
        int bytes = command.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > RconPacket.MAX_PAYLOAD_SENT) {
            return CompletableFuture.completedFuture(CommandResult.error("the command is " + bytes
                    + " bytes; RCON accepts at most " + RconPacket.MAX_PAYLOAD_SENT));
        }
        CompletableFuture<CommandResult> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                if (result.isCancelled()) {
                    return;
                }
                result.complete(execute(command, timeout));
            });
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(CommandResult.error("the RCON client is closed"));
        }
        return result;
    }

    private CommandResult execute(String command, Duration timeout) {
        int millis = (int) Math.max(1, Math.min(timeout.toMillis(), Integer.MAX_VALUE));
        try {
            Socket current = connection;
            if (current == null) {
                current = connect(millis);
                connection = current;
            }
            return CommandResult.ok(roundTrip(current, command, millis));
        } catch (AuthenticationException e) {
            // connect() has already closed the connection and it was never kept.
            return CommandResult.error(e.getMessage());
        } catch (ReplyTooLargeException e) {
            disconnect();
            return CommandResult.error(e.getMessage());
        } catch (SocketTimeoutException e) {
            disconnect();
            return CommandResult.error("RCON did not answer within " + millis + " ms; the command may still have taken effect");
        } catch (IOException e) {
            disconnect();
            return CommandResult.error("RCON failed: " + e);
        }
    }

    /** Opens a connection and logs in; on any failure the half-open connection is closed again. */
    private Socket connect(int millis) throws IOException {
        Socket fresh = sockets.get();
        try {
            fresh.connect(new InetSocketAddress(config.host(), config.port()), millis);
            fresh.setSoTimeout(millis);
            fresh.getOutputStream().write(new RconPacket(AUTH_ID, RconPacket.AUTH, config.password()).encode());
            RconPacket reply = RconPacket.read(fresh.getInputStream());
            // Some servers send an empty reply of the other type first; the answer to the login is a COMMAND packet.
            while (reply.type() != RconPacket.COMMAND) {
                reply = RconPacket.read(fresh.getInputStream());
            }
            if (reply.id() == RconPacket.AUTH_FAILED) {
                throw new AuthenticationException();
            }
            return fresh;
        } catch (IOException e) {
            closeQuietly(fresh);
            throw e;
        }
    }

    /** One command and its whole reply, within {@code millis} in total and {@link #MAX_REPLY_BYTES} bytes. */
    private String roundTrip(Socket connected, String command, int millis) throws IOException {
        long deadline = nanos.getAsLong() + millis * 1_000_000L;
        var out = connected.getOutputStream();
        out.write(new RconPacket(COMMAND_ID, RconPacket.COMMAND, command).encode());
        out.write(new RconPacket(END_MARKER_ID, RconPacket.RESPONSE, "").encode());
        StringBuilder reply = new StringBuilder();
        int bytes = 0;
        while (true) {
            // Each read waits at most what is left, so a server that keeps sending cannot hold the worker past the call's timeout.
            long left = (deadline - nanos.getAsLong()) / 1_000_000L;
            if (left < 1) {
                throw new SocketTimeoutException();
            }
            connected.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            RconPacket packet = RconPacket.read(connected.getInputStream());
            if (packet.id() == END_MARKER_ID) {
                return reply.toString();
            }
            if (packet.id() == COMMAND_ID) {
                bytes += packet.payload().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_REPLY_BYTES) {
                    throw new ReplyTooLargeException();
                }
                reply.append(packet.payload());
            }
        }
    }

    private void disconnect() {
        Socket old = connection;
        connection = null;
        if (old != null) {
            closeQuietly(old);
        }
    }

    private static void closeQuietly(Socket toClose) {
        try {
            toClose.close();
        } catch (IOException ignored) {
            // Nothing useful to do: the connection is being thrown away.
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
        disconnect();
    }

    /** The server sent more than {@link #MAX_REPLY_BYTES}; the connection is dropped, so the rest never reaches the next command. */
    private static final class ReplyTooLargeException extends IOException {
        ReplyTooLargeException() {
            super("the RCON reply is larger than " + MAX_REPLY_BYTES + " bytes; the connection was closed and the command may still have taken effect");
        }
    }

    /** The server rejected the password. The message never contains it. */
    private static final class AuthenticationException extends IOException {
        AuthenticationException() {
            super("RCON authentication failed: the server did not accept rcon.password");
        }
    }
}
