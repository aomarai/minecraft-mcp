package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/**
 * Client adapters only: acting on the open screen the way the player's own mouse and keyboard would, and no more.
 * Each call runs on the client's main thread and answers with a sentence saying what was done. It fails, with a
 * message that names the problem, if there is no screen, an overlay is blocking input, the element id no longer
 * matches, or the element cannot take the action.
 */
public interface ScreenControl extends GamePort {

    /** The most characters one {@link #type} call may send. */
    int MAX_TYPE_LENGTH = 256;
    /** The most mouse-wheel notches one {@link #scroll} call may turn, either way. */
    int MAX_SCROLL = 20;

    CompletableFuture<String> click(String element);

    /** Types into the focused text field. */
    CompletableFuture<String> type(String text);

    /** Scrolls by mouse-wheel notches over an element, or over the middle of the screen when {@code element} is empty. */
    CompletableFuture<String> scroll(String element, int notches);
}
