package dev.aomarai.minecraftmcp.rcon;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the answers to {@code data get entity <player> <path>}, the one vanilla command that tells a
 * server with no mod where a player is. The reply is text from the server, so it is parsed strictly: a
 * reply that does not look exactly right is an error, never a guess.
 */
final class EntityData {

    private static final String NUMBER = "(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)";
    private static final Pattern REPLY = Pattern.compile("^[A-Za-z0-9_]{1,16} has the following entity data: (.+)$", Pattern.DOTALL);
    private static final Pattern POSITION = Pattern.compile("^\\[" + NUMBER + "d, " + NUMBER + "d, " + NUMBER + "d]$");
    private static final Pattern DIMENSION = Pattern.compile("^\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"$");
    private static final Pattern UUID_INTS = Pattern.compile("^\\[I; (-?[0-9]+), (-?[0-9]+), (-?[0-9]+), (-?[0-9]+)]$");

    private EntityData() {
    }

    /** {@code [x, y, z]} from the reply to {@code data get entity <player> Pos}. */
    static double[] position(String reply) throws IOException {
        Matcher matcher = match(POSITION, value(reply), reply);
        // The pattern only lets through text that is a valid number.
        return new double[]{Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2)),
                Double.parseDouble(matcher.group(3))};
    }

    /** The dimension id from the reply to {@code data get entity <player> Dimension}. */
    static String dimension(String reply) throws IOException {
        return match(DIMENSION, value(reply), reply).group(1);
    }

    /** The UUID from the reply to {@code data get entity <player> UUID}, which the game writes as four ints. */
    static String uuid(String reply) throws IOException {
        Matcher matcher = match(UUID_INTS, value(reply), reply);
        try {
            long[] ints = new long[4];
            for (int i = 0; i < 4; i++) {
                ints[i] = Integer.parseInt(matcher.group(i + 1));
            }
            long most = (ints[0] << 32) | (ints[1] & 0xFFFFFFFFL);
            long least = (ints[2] << 32) | (ints[3] & 0xFFFFFFFFL);
            return new UUID(most, least).toString();
        } catch (NumberFormatException e) {
            throw unexpected(reply);
        }
    }

    private static String value(String reply) throws IOException {
        Matcher matcher = REPLY.matcher(reply.strip());
        if (!matcher.matches()) {
            throw unexpected(reply);
        }
        return matcher.group(1);
    }

    private static Matcher match(Pattern pattern, String value, String reply) throws IOException {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw unexpected(reply);
        }
        return matcher;
    }

    private static IOException unexpected(String reply) {
        String shown = reply.length() > 80 ? reply.substring(0, 80) + "..." : reply;
        return new IOException("unexpected reply from the server: " + shown.replaceAll("\\p{Cntrl}", " "));
    }
}
