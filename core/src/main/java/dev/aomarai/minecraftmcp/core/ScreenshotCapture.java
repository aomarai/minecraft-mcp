package dev.aomarai.minecraftmcp.core;

/**
 * Reserved for the client adapter (issue #38): capturing the game window. It has no methods yet; it
 * exists so that the client-only {@code take_screenshot} tool can declare the capability it needs,
 * and so that no dedicated-server adapter can ever provide it by accident.
 */
public interface ScreenshotCapture extends GamePort {
}
