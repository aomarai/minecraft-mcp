package dev.aomarai.minecraftmcp.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

/** What the client code needs that Minecraft 1.21.x names or does differently from 26.x. */
final class ClientCompat {

    private ClientCompat() {
    }

    /** Reads the current frame on the render thread and hands it over; the receiver closes the image. */
    static void grab(Minecraft minecraft, Consumer<NativeImage> receiver) {
        receiver.accept(Screenshot.takeScreenshot(minecraft.getMainRenderTarget()));
    }

    /** The pixels row by row as alpha, blue, green, red packed in an int. A copy: the image can be closed. */
    static int[] abgr(NativeImage image) {
        return image.getPixelsRGBA();
    }
}
