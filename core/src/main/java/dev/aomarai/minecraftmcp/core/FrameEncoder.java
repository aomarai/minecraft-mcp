package dev.aomarai.minecraftmcp.core;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Turns the pixels a client adapter read from the game window into a {@link Screenshot}: scaled down to fit the
 * requested size, opaque, and small enough to send. The pixel work is the same for every loader and version, so
 * it lives here where the quality gates apply; the adapter only reads the frame and calls {@link #encode}.
 */
public final class FrameEncoder {

    /** Each retry keeps this share of the size, so a frame that does not fit at first still comes out. */
    private static final double SHRINK_STEP = 0.75;

    private FrameEncoder() {
    }

    /**
     * @param abgr      pixels row by row, top to bottom, as Minecraft's {@code NativeImage} packs them: alpha in
     *                  the top byte, then blue, green, red. Alpha is ignored; the picture is always opaque
     * @param maxWidth  the picture is scaled down, keeping its proportions, to fit within this
     * @param maxHeight and this; the image is never scaled up
     * @throws IOException if the PNG cannot be written
     */
    public static Screenshot encode(int[] abgr, int width, int height, int maxWidth, int maxHeight) throws IOException {
        return encode(abgr, width, height, maxWidth, maxHeight, Screenshot.MAX_BYTES);
    }

    /** {@link #encode} with the byte limit as a parameter, so a test can put a picture exactly on it. */
    static Screenshot encode(int[] abgr, int width, int height, int maxWidth, int maxHeight, int maxBytes)
            throws IOException {
        if (width < 1 || height < 1 || abgr.length != width * height) {
            throw new IllegalArgumentException("expected " + width + "x" + height + " pixels, got " + abgr.length);
        }
        if (maxWidth < 1 || maxHeight < 1) {
            throw new IllegalArgumentException("the size limit must be positive, got " + maxWidth + "x" + maxHeight);
        }
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < abgr.length; i++) {
            source.setRGB(i % width, i / width, swapRedAndBlue(abgr[i]));
        }

        // A frame that is too big in bytes is shrunk further until it fits. This ends: a 1x1 picture is a few bytes.
        double scale = Math.min(1.0, Math.min((double) maxWidth / width, (double) maxHeight / height));
        int w;
        int h;
        byte[] png;
        do {
            w = Math.max(1, (int) (width * scale));
            h = Math.max(1, (int) (height * scale));
            png = png(scaled(source, w, h));
            scale *= SHRINK_STEP;
        } while (png.length > maxBytes);
        return new Screenshot(png, w, h);
    }

    private static int swapRedAndBlue(int abgr) {
        return (abgr & 0x0000FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
    }

    private static BufferedImage scaled(BufferedImage source, int width, int height) {
        AffineTransform transform = AffineTransform.getScaleInstance(
                (double) width / source.getWidth(), (double) height / source.getHeight());
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        return new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR).filter(source, target);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Every JDK has a PNG writer, so the result (false only when none exists) is not checked.
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
