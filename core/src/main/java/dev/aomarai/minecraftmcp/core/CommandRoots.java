package dev.aomarai.minecraftmcp.core;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How a command's first word is compared: stripped, lower-cased, and without the {@code minecraft:}
 * namespace. Vanilla accepts {@code minecraft:kill} as an alias of {@code kill}, so the policy must
 * see them as the same command. Other namespaces ({@code mymod:wipe}) are kept: they are different
 * commands.
 */
final class CommandRoots {

    private static final String VANILLA_NAMESPACE = "minecraft:";

    private CommandRoots() {
    }

    static String normalize(String root) {
        String lower = root.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith(VANILLA_NAMESPACE) ? lower.substring(VANILLA_NAMESPACE.length()) : lower;
    }

    /** Normalizes operator-supplied command names, rejecting anything that is not a single word. */
    static Set<String> normalizeAll(Set<String> roots, String what) {
        return roots.stream().map(root -> {
            String normalized = normalize(root);
            if (normalized.isEmpty() || normalized.chars().anyMatch(c -> Character.isWhitespace(c) || c == '/')) {
                throw new IllegalArgumentException(
                        what + " must be single command names without spaces or slashes: '" + root + "'");
            }
            return normalized;
        }).collect(Collectors.toUnmodifiableSet());
    }
}
