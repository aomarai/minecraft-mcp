package dev.aomarai.minecraftmcp.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Consumer;

/** What the client code needs that Minecraft 1.21.x names or does differently from 26.x. */
final class ClientCompat {

    private ClientCompat() {
    }

    /** Reads the current frame on the render thread and hands it over; the receiver closes the image. */
    static void grab(Minecraft minecraft, Consumer<NativeImage> receiver) {
        receiver.accept(Screenshot.takeScreenshot(minecraft.getMainRenderTarget()));
    }

    /** The open screen, or null. */
    static Screen screen(Minecraft minecraft) {
        return minecraft.screen;
    }

    /** The loading or similar overlay drawn over everything, or null. */
    static Overlay overlay(Minecraft minecraft) {
        return minecraft.getOverlay();
    }

    /** A left click, press and release, at a point in GUI coordinates; whether the screen took the press. */
    static boolean click(Screen screen, double x, double y) {
        boolean taken = screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
        return taken;
    }

    /** One typed character. */
    static void type(Screen screen, int codePoint) {
        for (char c : Character.toChars(codePoint)) {
            screen.charTyped(c, 0);
        }
    }

    /** The pixels row by row as alpha, blue, green, red packed in an int. A copy: the image can be closed. */
    static int[] abgr(NativeImage image) {
        return image.getPixelsRGBA();
    }
}
