package dev.aomarai.minecraftmcp.core;

import java.util.Locale;
import java.util.Objects;

/**
 * One thing on a game screen that can be looked at or clicked. The label is text from the game or a mod and so
 * untrusted; it is cleaned and cut to {@value #MAX_LABEL} characters.
 *
 * @param id     {@code <index>:<role>}, valid for the screen as it was when listed. A click on an id whose role no
 *               longer matches is refused, so a screen that changed in between is never clicked by mistake
 * @param x      position and size in the game's GUI coordinates, which are not window pixels
 */
public record ScreenElement(String id, Role role, String label, int x, int y, int width, int height,
                            boolean enabled, boolean focused) {

    public static final int MAX_LABEL = 256;

    public enum Role {
        BUTTON, CHECKBOX, SLIDER, TEXT_FIELD, SLOT, LIST, LABEL, OTHER;

        /** The name clients see and filter by: lower case, as in {@code text_field}. */
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public ScreenElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        label = UntrustedText.clean(label, MAX_LABEL);
    }

    /** The id of the element at {@code index} with this role. */
    public static String idOf(int index, Role role) {
        return index + ":" + role.json();
    }
}
