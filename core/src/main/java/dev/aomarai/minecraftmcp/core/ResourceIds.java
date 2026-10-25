package dev.aomarai.minecraftmcp.core;

import java.util.regex.Pattern;

/**
 * Validates and normalizes Minecraft resource locations ({@code namespace:path}) that come from a
 * client, before they reach the game. A bare name means the {@code minecraft} namespace.
 */
final class ResourceIds {

    private static final Pattern VALID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private ResourceIds() {
    }

    static String parse(String raw, String what) {
        String id = raw.strip();
        String full = id.contains(":") ? id : "minecraft:" + id;
        if (!VALID.matcher(full).matches()) {
            throw new IllegalArgumentException(
                    what + " must be a resource location like minecraft:stone (lower case letters, digits and _.-/), got: " + raw);
        }
        return full;
    }
}
