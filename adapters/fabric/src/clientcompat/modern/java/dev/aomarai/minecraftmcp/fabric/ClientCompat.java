package dev.aomarai.minecraftmcp.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

/** What the client code needs that Minecraft 1.21.x names or does differently from 26.x. */
final class ClientCompat {

    private ClientCompat() {
    }

    /** Reads the current frame and hands it over once the GPU has delivered it; the receiver closes the image. */
    static void grab(Minecraft minecraft, Consumer<NativeImage> receiver) {
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), receiver);
    }

    /** The pixels row by row as alpha, blue, green, red packed in an int. A copy: the image can be closed. */
    static int[] abgr(NativeImage image) {
        return image.getPixelsABGR();
    }
}
