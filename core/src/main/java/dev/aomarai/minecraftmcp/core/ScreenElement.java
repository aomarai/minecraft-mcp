package dev.aomarai.minecraftmcp.core;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One thing on a game screen that can be looked at or clicked. The label is text from the game or a mod and so
 * untrusted; it is cleaned and cut to {@value #MAX_LABEL} characters.
 *
 * @param id     {@code <index>:<role>:<generation>:<label hash>}, valid for the screen as it was when listed. The
 *               generation changes whenever a different screen opens. A click on an id that no longer matches the
 *               element at that position (screen, role or label) is refused, so a screen that changed in between
 *               is never clicked by mistake
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

    /** The id of the element at {@code index} with this role and label, on the screen of this generation. */
    public static String idOf(int index, Role role, long generation, String label) {
        int hash = UntrustedText.clean(label, MAX_LABEL).hashCode();
        return index + ":" + role.json() + ":" + generation + ":" + Integer.toHexString(hash);
    }

    /**
     * The position in {@code current} of the element {@code id} was made for. Refused when the screen has changed
     * since the id was listed: nothing at that position carries the same id.
     */
    public static int resolve(List<ScreenElement> current, String id) {
        int index;
        try {
            index = Integer.parseInt(id.split(":", 2)[0]);
        } catch (NumberFormatException e) {
            index = -1;
        }
        if (index < 0 || index >= current.size() || !current.get(index).id().equals(id)) {
            throw new IllegalStateException("no element " + id + " on this screen; it may have changed, call get_screen again");
        }
        return index;
    }
}
