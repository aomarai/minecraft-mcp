package dev.aomarai.minecraftmcp.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import dev.aomarai.minecraftmcp.core.FrameEncoder;
import dev.aomarai.minecraftmcp.core.Screenshot;
import dev.aomarai.minecraftmcp.core.ScreenshotCapture;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;

/**
 * Captures the game window. The frame is read on the client's render thread, which is quick; turning it into a
 * bounded PNG happens on another thread so the game never waits for that. Nothing is written to disk.
 */
final class FabricClientScreenshots implements ScreenshotCapture {

    @Override
    public CompletableFuture<Screenshot> capture(int maxWidth, int maxHeight) {
        CompletableFuture<Screenshot> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (result.isDone()) {
                return; // the call timed out before the game thread got to it
            }
            try {
                ClientCompat.grab(minecraft, image -> encode(image, maxWidth, maxHeight, result));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static void encode(NativeImage image, int maxWidth, int maxHeight, CompletableFuture<Screenshot> result) {
        int width;
        int height;
        int[] pixels;
        try (image) {
            width = image.getWidth();
            height = image.getHeight();
            pixels = ClientCompat.abgr(image);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                result.complete(FrameEncoder.encode(pixels, width, height, maxWidth, maxHeight));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
    }
}
