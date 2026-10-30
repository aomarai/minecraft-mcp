package dev.aomarai.minecraftmcp.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * One captured frame as PNG bytes. The bounds are enforced here, in the loader-agnostic core, so that no
 * adapter can hand a client an oversized image: at most {@value #MAX_WIDTH} x {@value #MAX_HEIGHT} pixels and
 * {@value #MAX_BYTES} bytes.
 */
public record Screenshot(byte[] png, int width, int height) {

    public static final int MAX_WIDTH = 1280;
    public static final int MAX_HEIGHT = 720;
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    public Screenshot {
        Objects.requireNonNull(png, "png");
        if (width < 1 || width > MAX_WIDTH) {
            throw new IllegalArgumentException("width must be 1-" + MAX_WIDTH + ", got " + width);
        }
        if (height < 1 || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("height must be 1-" + MAX_HEIGHT + ", got " + height);
        }
        if (png.length > MAX_BYTES) {
            throw new IllegalArgumentException("png must be at most " + MAX_BYTES + " bytes, got " + png.length);
        }
        if (png.length < PNG_SIGNATURE.length
                || !Arrays.equals(png, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw new IllegalArgumentException("png is not a PNG image");
        }
        png = png.clone();
    }

    @Override
    public byte[] png() {
        return png.clone();
    }

    /** Frames are equal when their pixels (bytes) and size are, not when they are the same array. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Screenshot that
                && width == that.width && height == that.height && Arrays.equals(png, that.png);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(png) + width) + height;
    }

    /** The size, never the image: a frame in a log or a failed assertion must not print hundreds of kilobytes. */
    @Override
    public String toString() {
        return "Screenshot[width=" + width + ", height=" + height + ", png=" + png.length + " bytes]";
    }

    /** What the audit log records instead of the image itself. */
    public String describe() {
        return "image/png " + width + "x" + height + ", " + png.length + " bytes";
    }
}
