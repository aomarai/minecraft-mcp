package dev.aomarai.minecraftmcp.rcon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RconPacketTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] frame(int length, int id, int type, byte[] body) {
        ByteBuffer out = ByteBuffer.allocate(4 + 8 + body.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(length).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
        return out.array();
    }

    @Test
    void aPacketIsEncodedLittleEndianWithItsLengthAndTwoTrailingZeros() {
        byte[] encoded = new RconPacket(2, RconPacket.COMMAND, "hi").encode();

        // length 12 = id(4) + type(4) + "hi"(2) + 2 zeros
        assertArrayEquals(bytes(12, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0, 'h', 'i', 0, 0), encoded);
    }

    @Test
    void anEmptyPayloadIsJustTheFrame() {
        byte[] encoded = new RconPacket(0x01020304, RconPacket.AUTH, "").encode();

        assertArrayEquals(bytes(10, 0, 0, 0, 4, 3, 2, 1, 3, 0, 0, 0, 0, 0), encoded);
    }

    @Test
    void thePayloadIsUtf8AndTheLengthCountsBytesNotCharacters() {
        byte[] encoded = new RconPacket(1, RconPacket.COMMAND, "é").encode();

        assertEquals(10 + 2, ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertArrayEquals(bytes(0xC3, 0xA9), java.util.Arrays.copyOfRange(encoded, 12, 14));
    }

    @Test
    void theLargestPayloadAClientMaySendIsWhatFitsIn1460Bytes() {
        assertEquals(1446, RconPacket.MAX_PAYLOAD_SENT);
        assertEquals(1460, new RconPacket(1, RconPacket.COMMAND, "x".repeat(1446)).encode().length);
    }

    @Test
    void aPacketIsReadBackFromItsEncoding() throws IOException {
        RconPacket sent = new RconPacket(77, RconPacket.RESPONSE, "héllo\nworld");

        assertEquals(sent, RconPacket.read(new ByteArrayInputStream(sent.encode())));
    }

    @Test
    void severalPacketsAreReadOneAfterTheOther() throws IOException {
        byte[] first = new RconPacket(1, RconPacket.COMMAND, "a").encode();
        byte[] second = new RconPacket(2, RconPacket.RESPONSE, "b").encode();
        ByteArrayInputStream in = new ByteArrayInputStream(concat(first, second));

        assertEquals("a", RconPacket.read(in).payload());
        assertEquals("b", RconPacket.read(in).payload());
    }

    @Test
    void negativeIdsSurviveTheRoundTrip() throws IOException {
        RconPacket read = RconPacket.read(new ByteArrayInputStream(new RconPacket(-1, RconPacket.COMMAND, "").encode()));

        assertEquals(-1, read.id());
        assertEquals(RconPacket.COMMAND, read.type());
    }

    @Test
    void theShortestAndLongestLegalPacketsAreAccepted() throws IOException {
        assertEquals("", RconPacket.read(new ByteArrayInputStream(frame(10, 5, 0, new byte[0]))).payload());

        String longest = "y".repeat(4096);
        RconPacket read = RconPacket.read(new ByteArrayInputStream(frame(10 + 4096, 5, 0, longest.getBytes(StandardCharsets.UTF_8))));
        assertEquals(longest, read.payload());
    }

    @Test
    void aLengthOutsideWhatTheProtocolAllowsIsRejected() {
        var arg = new ByteArrayInputStream(frame(9, 1, 0, new byte[0]));
        IOException tooShort = assertThrows(IOException.class, () -> RconPacket.read(arg));
        assertEquals("malformed RCON packet: length 9", tooShort.getMessage());

        var arg2 = new ByteArrayInputStream(frame(10 + 4097, 1, 0, new byte[0]));
        IOException tooLong = assertThrows(IOException.class, () -> RconPacket.read(arg2));
        assertEquals("malformed RCON packet: length 4107", tooLong.getMessage());

        var arg3 = new ByteArrayInputStream(frame(-5, 1, 0, new byte[0]));
        assertThrows(IOException.class, () -> RconPacket.read(arg3));
    }

    @Test
    void aStreamThatEndsInsideAPacketIsAnError() {
        byte[] whole = new RconPacket(1, RconPacket.COMMAND, "hello").encode();

        var arg4 = new ByteArrayInputStream(new byte[0]);
        assertThrows(EOFException.class, () -> RconPacket.read(arg4));
        var arg5 = new ByteArrayInputStream(java.util.Arrays.copyOf(whole, whole.length - 1));
        assertThrows(EOFException.class, () -> RconPacket.read(arg5));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
