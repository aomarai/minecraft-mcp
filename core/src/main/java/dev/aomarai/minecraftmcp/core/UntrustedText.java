package dev.aomarai.minecraftmcp.core;

import java.util.Objects;

/**
 * Makes text that came from the game or a player safe to carry: control characters (including line breaks) become
 * spaces and anything over the limit is cut, so the result is short and on one line. It is still untrusted: this
 * makes it safe to carry, not safe to obey.
 */
final class UntrustedText {

    private UntrustedText() {
    }

    static String clean(String raw, int max) {
        String text = Objects.requireNonNullElse(raw, "").strip();
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        text.chars().limit(max).forEach(c -> out.append(Character.isISOControl(c) ? ' ' : (char) c));
        return text.length() > max ? out + "..." : out.toString();
    }
}
