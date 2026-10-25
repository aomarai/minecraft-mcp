package dev.aomarai.minecraftmcp.rcon;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the universal tier needs to know about the game server it controls. Only RCON is required; each
 * optional part switches on the tools that depend on it.
 *
 * @param rcon            the remote console: lets commands (and so the typed write and admin tools) run
 * @param queryPort       the Query protocol's UDP port, on the same host as RCON: gives the server's version
 *                        and who is online
 * @param serverDirectory the game server's folder, when this process can read it (it runs on the same
 *                        machine): gives the log, the chat/join/leave events and who is an operator
 */
public record UniversalConfig(RconConfig rcon, OptionalInt queryPort, Optional<Path> serverDirectory) {

    public UniversalConfig {
        Objects.requireNonNull(rcon, "rcon");
        Objects.requireNonNull(queryPort, "queryPort");
        Objects.requireNonNull(serverDirectory, "serverDirectory");
        if (queryPort.isPresent() && (queryPort.getAsInt() < 1 || queryPort.getAsInt() > 65535)) {
            throw new IllegalArgumentException("queryPort must be between 1 and 65535");
        }
    }
}
