package dev.aomarai.minecraftmcp.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.function.Consumer;

/** What the client code needs that Minecraft 1.21.x names or does differently from 26.x. */
final class ClientCompat {

    private ClientCompat() {
    }

    /** Reads the current frame and hands it over once the GPU has delivered it; the receiver closes the image. */
    static void grab(Minecraft minecraft, Consumer<NativeImage> receiver) {
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), receiver);
    }

    /** The open screen, or null. */
    static Screen screen(Minecraft minecraft) {
        return minecraft.gui.screen();
    }

    /** The loading or similar overlay drawn over everything, or null. */
    static Overlay overlay(Minecraft minecraft) {
        return minecraft.gui.overlay();
    }

    /** A left click, press and release, at a point in GUI coordinates; whether the screen took the press. */
    static boolean click(Screen screen, double x, double y) {
        MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
        boolean taken = screen.mouseClicked(event, false);
        screen.mouseReleased(event);
        return taken;
    }

    /** One typed character. */
    static void type(Screen screen, int codePoint) {
        screen.charTyped(new CharacterEvent(codePoint));
    }

    /** The pixels row by row as alpha, blue, green, red packed in an int. A copy: the image can be closed. */
    static int[] abgr(NativeImage image) {
        return image.getPixelsABGR();
    }
}
