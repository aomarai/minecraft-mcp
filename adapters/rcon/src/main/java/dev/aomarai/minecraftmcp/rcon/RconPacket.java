package dev.aomarai.minecraftmcp.rcon;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One packet of the RCON protocol: a little-endian length, a request id, a type, a payload and two
 * trailing zero bytes. The length counts everything after itself.
 */
record RconPacket(int id, int type, String payload) {

    /** Sent with the password. */
    static final int AUTH = 3;
    /** Sent with a command; the server also answers authentication with this type. */
    static final int COMMAND = 2;
    /** Carries the server's reply to a command. */
    static final int RESPONSE = 0;

    /** The id of an authentication reply when the password was wrong. */
    static final int AUTH_FAILED = -1;

    /** Request id, type and the two trailing zero bytes. */
    private static final int FRAME = 10;
    /** The largest packet a client may send is 1460 bytes: the length field, the frame and the payload. */
    static final int MAX_PAYLOAD_SENT = 1460 - 4 - FRAME;
    /** The server splits longer replies into several packets, so no single payload is longer than this. */
    private static final int MAX_PAYLOAD_RECEIVED = 4096;

    byte[] encode() {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(4 + FRAME + body.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(FRAME + body.length).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
        return out.array();
    }

    static RconPacket read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = Integer.reverseBytes(data.readInt());
        if (length < FRAME || length > FRAME + MAX_PAYLOAD_RECEIVED) {
            throw new IOException("malformed RCON packet: length " + length);
        }
        byte[] rest = new byte[length];
        data.readFully(rest);
        ByteBuffer buffer = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        String payload = new String(rest, 8, length - FRAME, StandardCharsets.UTF_8);
        return new RconPacket(id, type, payload);
    }
}
