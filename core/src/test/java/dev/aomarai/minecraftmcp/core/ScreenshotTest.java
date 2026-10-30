package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotTest {

    private static byte[] png(int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(FakeGame.png(), 0, bytes, 0, FakeGame.png().length);
        return bytes;
    }

    @Test
    void keepsTheImageAndDescribesItWithoutTheBytes() {
        Screenshot shot = new Screenshot(FakeGame.png(), 1280, 720);

        assertArrayEquals(FakeGame.png(), shot.png());
        assertEquals(1280, shot.width());
        assertEquals(720, shot.height());
        assertEquals("image/png 1280x720, 11 bytes", shot.describe());
    }

    @Test
    void copiesTheBytesIn_andOut() {
        byte[] bytes = FakeGame.png();
        Screenshot shot = new Screenshot(bytes, 1, 1);

        bytes[8] = 99;
        shot.png()[9] = 99;

        assertArrayEquals(FakeGame.png(), shot.png());
    }

    @Test
    void widthAndHeightAreBoundedAtBothEnds() {
        assertDoesNotThrow(() -> new Screenshot(FakeGame.png(), 1, 1));
        assertDoesNotThrow(() -> new Screenshot(FakeGame.png(), Screenshot.MAX_WIDTH, Screenshot.MAX_HEIGHT));
        assertEquals("width must be 1-1280, got 0",
                assertThrows(IllegalArgumentException.class, () -> new Screenshot(FakeGame.png(), 0, 1)).getMessage());
        assertEquals("width must be 1-1280, got 1281",
                assertThrows(IllegalArgumentException.class, () -> new Screenshot(FakeGame.png(), 1281, 1)).getMessage());
        assertEquals("height must be 1-720, got 0",
                assertThrows(IllegalArgumentException.class, () -> new Screenshot(FakeGame.png(), 1, 0)).getMessage());
        assertEquals("height must be 1-720, got 721",
                assertThrows(IllegalArgumentException.class, () -> new Screenshot(FakeGame.png(), 1, 721)).getMessage());
    }

    @Test
    void sizeIsBounded() {
        assertDoesNotThrow(() -> new Screenshot(png(Screenshot.MAX_BYTES), 1, 1));
        assertEquals("png must be at most 2097152 bytes, got 2097153",
                assertThrows(IllegalArgumentException.class, () -> new Screenshot(png(Screenshot.MAX_BYTES + 1), 1, 1)).getMessage());
    }

    @Test
    void onlyAPngIsAccepted() {
        assertThrows(NullPointerException.class, () -> new Screenshot(null, 1, 1));
        assertEquals("png is not a PNG image",
                assertThrows(IllegalArgumentException.class, () -> new Screenshot(new byte[]{1, 2, 3}, 1, 1)).getMessage());
        byte[] almost = FakeGame.png();
        almost[7] = 0;
        assertEquals("png is not a PNG image",
                assertThrows(IllegalArgumentException.class, () -> new Screenshot(almost, 1, 1)).getMessage());
        byte[] justTheSignature = new byte[8];
        System.arraycopy(FakeGame.png(), 0, justTheSignature, 0, 8);
        assertDoesNotThrow(() -> new Screenshot(justTheSignature, 1, 1));
    }

    @Test
    void framesWithTheSameBytesAndSizeAreEqualAndShareAHash() {
        Screenshot first = new Screenshot(FakeGame.png(), 640, 360);
        Screenshot second = new Screenshot(FakeGame.png(), 640, 360); // another array, same pixels

        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first, first);
    }

    @Test
    void framesThatDifferInBytesWidthHeightOrTypeAreNotEqual() {
        Screenshot shot = new Screenshot(FakeGame.png(), 640, 360);
        byte[] other = FakeGame.png();
        other[10] = 42;

        assertNotEquals(shot, new Screenshot(other, 640, 360));
        assertNotEquals(shot, new Screenshot(FakeGame.png(), 641, 360));
        assertNotEquals(shot, new Screenshot(FakeGame.png(), 640, 361));
        assertNotEquals(shot, null);
        assertNotEquals(shot, "image/png 640x360, 11 bytes");
        assertNotEquals(shot.hashCode(), new Screenshot(other, 640, 360).hashCode());
        assertNotEquals(shot.hashCode(), new Screenshot(FakeGame.png(), 641, 360).hashCode());
        assertNotEquals(shot.hashCode(), new Screenshot(FakeGame.png(), 640, 361).hashCode());
    }

    @Test
    void toStringShowsTheSizeAndNeverTheBytesOrAnArrayIdentity() {
        assertEquals("Screenshot[width=640, height=360, png=11 bytes]",
                new Screenshot(FakeGame.png(), 640, 360).toString());
    }
}
