package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FrameEncoderTest {

    private static final int RED = 0xFF0000FF;
    private static final int BLUE = 0xFFFF0000;

    private static BufferedImage decode(Screenshot shot) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(shot.png()));
    }

    private static int[] solid(int width, int height, int abgr) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, abgr);
        return pixels;
    }

    @Test
    void anImageThatFitsIsEncodedAtItsOwnSizeWithRedAndBlueTheRightWayRound() throws IOException {
        int[] pixels = {RED, BLUE, 0xFF00FF00, 0x00FFFFFF, RED, BLUE};

        Screenshot shot = FrameEncoder.encode(pixels, 3, 2, 1280, 720);

        assertEquals(3, shot.width());
        assertEquals(2, shot.height());
        BufferedImage image = decode(shot);
        assertEquals(3, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
        assertEquals(0xFF0000FF, image.getRGB(1, 0));
        assertEquals(0xFF00FF00, image.getRGB(2, 0));
        assertEquals(0xFFFFFFFF, image.getRGB(0, 1), "alpha is ignored");
        assertEquals(0xFFFF0000, image.getRGB(1, 1));
    }

    @Test
    void aLargeImageIsScaledDownKeepingItsProportionsAndNeverUp() throws IOException {
        Screenshot wide = FrameEncoder.encode(solid(2560, 720, RED), 2560, 720, 1280, 720);
        assertEquals(1280, wide.width());
        assertEquals(360, wide.height());
        assertEquals(0xFFFF0000, decode(wide).getRGB(5, 5));

        Screenshot tall = FrameEncoder.encode(solid(640, 1440, RED), 640, 1440, 1280, 720);
        assertEquals(320, tall.width());
        assertEquals(720, tall.height());

        Screenshot small = FrameEncoder.encode(solid(10, 10, RED), 10, 10, 1280, 720);
        assertEquals(10, small.width());
        assertEquals(10, small.height());

        Screenshot exactly = FrameEncoder.encode(solid(1280, 720, RED), 1280, 720, 1280, 720);
        assertEquals(1280, exactly.width());
        assertEquals(720, exactly.height());
    }

    @Test
    void theRequestedLimitCanBeSmallerThanTheDefault() throws IOException {
        Screenshot shot = FrameEncoder.encode(solid(100, 100, RED), 100, 100, 50, 80);

        assertEquals(50, shot.width());
        assertEquals(50, shot.height());
    }

    @Test
    void aPictureThatDoesNotFitInTheByteLimitIsShrunkUntilItDoes() throws IOException {
        int width = 1280;
        int height = 720;
        int[] noise = new int[width * height];
        Random random = new Random(7);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = random.nextInt();
        }

        Screenshot shot = FrameEncoder.encode(noise, width, height, width, height);

        assertTrue(shot.png().length <= Screenshot.MAX_BYTES);
        assertTrue(shot.width() < width && shot.height() < height, shot.describe());
        assertEquals(shot.width(), decode(shot).getWidth());
        assertEquals(shot.height(), decode(shot).getHeight());
    }

    @Test
    void scalingDownAveragesNeighbouringPixelsInsteadOfPickingOne() throws IOException {
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;

        Screenshot shot = FrameEncoder.encode(new int[]{black, white}, 2, 1, 1, 1);

        int grey = decode(shot).getRGB(0, 0) & 0xFF;
        assertTrue(grey > 100 && grey < 160, "grey was " + grey);
    }

    @Test
    void aPictureExactlyOnTheByteLimitIsKeptAndOneByteOverIsShrunk() throws IOException {
        int[] pixels = new int[64 * 64];
        Random random = new Random(3);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt();
        }
        int exact = FrameEncoder.encode(pixels, 64, 64, 64, 64).png().length;

        Screenshot kept = FrameEncoder.encode(pixels, 64, 64, 64, 64, exact);
        Screenshot shrunk = FrameEncoder.encode(pixels, 64, 64, 64, 64, exact - 1);

        assertEquals(64, kept.width());
        assertTrue(shrunk.width() < 64, "width was " + shrunk.width());
    }

    @Test
    void theArgumentsAreChecked() {
        assertEquals("expected 2x2 pixels, got 3",
                assertThrows(IllegalArgumentException.class, () -> FrameEncoder.encode(new int[3], 2, 2, 10, 10)).getMessage());
        assertEquals("expected 0x1 pixels, got 0",
                assertThrows(IllegalArgumentException.class, () -> FrameEncoder.encode(new int[0], 0, 1, 10, 10)).getMessage());
        assertEquals("expected 1x0 pixels, got 0",
                assertThrows(IllegalArgumentException.class, () -> FrameEncoder.encode(new int[0], 1, 0, 10, 10)).getMessage());
        assertEquals("the size limit must be positive, got 0x5",
                assertThrows(IllegalArgumentException.class, () -> FrameEncoder.encode(new int[1], 1, 1, 0, 5)).getMessage());
        assertEquals("the size limit must be positive, got 5x0",
                assertThrows(IllegalArgumentException.class, () -> FrameEncoder.encode(new int[1], 1, 1, 5, 0)).getMessage());
        assertDoesNotThrow(() -> FrameEncoder.encode(new int[1], 1, 1, 1, 1));
    }
}
