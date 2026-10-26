package dev.aomarai.minecraftmcp.core;

import java.util.List;
import java.util.Objects;

/**
 * The current game screen as a flat list of elements. An empty {@code screen} means no screen is open (the player
 * is in the world). {@code overlay} names a loading or similar overlay drawn over the screen, which blocks input
 * until it goes; it is empty when there is none.
 *
 * @param screen  the screen's class name, e.g. {@code TitleScreen}; text, so untrusted
 * @param title   the screen's title; untrusted
 * @param elements at most {@value #MAX_ELEMENTS}
 */
public record ScreenSnapshot(String screen, String title, String overlay, List<ScreenElement> elements) {

    public static final int MAX_ELEMENTS = 300;
    private static final int MAX_NAME = 128;

    public ScreenSnapshot {
        screen = UntrustedText.clean(screen, MAX_NAME);
        title = UntrustedText.clean(title, ScreenElement.MAX_LABEL);
        overlay = UntrustedText.clean(overlay, MAX_NAME);
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        if (elements.size() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("a screen lists at most " + MAX_ELEMENTS + " elements, got " + elements.size());
        }
    }
}
