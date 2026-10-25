package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/**
 * Client adapters only (issue #38): capturing the game window. No dedicated-server adapter can provide it,
 * because the {@code take_screenshot} tool is on the client side.
 *
 * <p>The capture stays in memory. Nothing is written to disk, so there is no path to keep inside the config
 * directory.</p>
 */
public interface ScreenshotCapture extends GamePort {

    /**
     * Captures the current frame, scaled down to fit within {@code maxWidth} x {@code maxHeight} if it is larger,
     * and completes on the client main thread's schedule. The core rejects anything that exceeds
     * {@link Screenshot}'s bounds.
     */
    CompletableFuture<Screenshot> capture(int maxWidth, int maxHeight);
}
