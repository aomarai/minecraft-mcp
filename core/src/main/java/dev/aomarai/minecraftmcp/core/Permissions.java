package dev.aomarai.minecraftmcp.core;

import java.util.List;

/**
 * Who has special access on the game server. This describes the server's own lists; it grants the AI
 * nothing (the AI never inherits operator rights).
 */
public record Permissions(List<Operator> operators, boolean whitelistEnabled, List<Whitelisted> whitelist) {

    public Permissions {
        operators = List.copyOf(operators);
        whitelist = List.copyOf(whitelist);
    }

    /** @param level vanilla permission level, 1 to 4 */
    public record Operator(String name, String uuid, int level) {
    }

    public record Whitelisted(String name, String uuid) {
    }
}
