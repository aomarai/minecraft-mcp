package dev.aomarai.minecraftmcp.core;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Builds the exact command a typed tool runs from its validated arguments.
 *
 * <p>This is why typed tools are safer than free-form {@code run_command}: every argument has a
 * fixed shape, so nothing a caller sends can add a second command, a selector such as {@code @a},
 * or another argument. Player names are a strict word, ids are resource locations, numbers are
 * bounded and written without exponents, and free text (a message, a reason) may not contain
 * control characters, so it cannot span lines.</p>
 */
final class CommandBuilder {

    static final int MAX_COORDINATE = 30_000_000;
    static final int MAX_TEXT = 256;
    static final int MAX_ITEM_COUNT = 6400;

    private static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_.]{1,32}");

    private CommandBuilder() {
    }

    /** {@code dimension} and {@code block} are resource locations; the box is inclusive. */
    static String fill(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String block) {
        return inDimension(dimension) + "fill " + coordinate(minX, "min_x") + " " + coordinate(minY, "min_y") + " "
                + coordinate(minZ, "min_z") + " " + coordinate(maxX, "max_x") + " " + coordinate(maxY, "max_y") + " "
                + coordinate(maxZ, "max_z") + " " + ResourceIds.parse(block, "block");
    }

    /** {@code dimension} may be empty, meaning where the player already is. */
    static String teleport(String player, String dimension, double x, double y, double z) {
        String prefix = dimension.isEmpty() ? "" : inDimension(dimension);
        return prefix + "tp " + player(player) + " " + position(x, "x") + " " + position(y, "y") + " " + position(z, "z");
    }

    static String give(String player, String item, int count) {
        if (count < 1 || count > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_ITEM_COUNT);
        }
        return "give " + player(player) + " " + ResourceIds.parse(item, "item") + " " + count;
    }

    /** {@code player} may be empty, meaning everyone. */
    static String message(String player, String text) {
        String message = text(text, "message");
        return player.isEmpty() ? "say " + message : "tell " + player(player) + " " + message;
    }

    static String kick(String player, String reason) {
        return "kick " + player(player) + optionalReason(reason);
    }

    static String ban(String player, String reason) {
        return "ban " + player(player) + optionalReason(reason);
    }

    static String saveWorld() {
        return "save-all flush";
    }

    static String stopServer() {
        return "stop";
    }

    static String player(String name) {
        if (!PLAYER.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "player must be a player name: 1 to 32 letters, digits, '_' or '.', got: " + name);
        }
        return name;
    }

    private static String inDimension(String dimension) {
        return "execute in " + ResourceIds.parse(dimension, "dimension") + " run ";
    }

    private static String optionalReason(String reason) {
        return reason.isEmpty() ? "" : " " + text(reason, "reason");
    }

    /** Free text on one line, at most {@value #MAX_TEXT} characters. */
    private static String text(String raw, String what) {
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException(what + " must be at most " + MAX_TEXT + " characters");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " must not contain control characters or line breaks");
        }
        return text;
    }

    private static String coordinate(int value, String what) {
        if (Math.abs((long) value) > MAX_COORDINATE) {
            throw new IllegalArgumentException(what + " must be between -" + MAX_COORDINATE + " and " + MAX_COORDINATE);
        }
        return Integer.toString(value);
    }

    /** A position written in plain decimal, never with an exponent, which the game would not parse. */
    private static String position(double value, String what) {
        if (!(Math.abs(value) <= MAX_COORDINATE)) {
            throw new IllegalArgumentException(what + " must be between -" + MAX_COORDINATE + " and " + MAX_COORDINATE);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
