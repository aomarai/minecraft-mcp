package dev.aomarai.minecraftmcp.core;

/** A capability an adapter may provide. Each one corresponds to a {@link GamePort}. */
public enum Capability {
    /** {@link RuntimeInfo} */
    RUNTIME_INFO,
    /** {@link PlayerReader} */
    PLAYERS,
    /** {@link WorldReader} */
    WORLD,
    /** {@link CommandRunner} */
    COMMANDS
}
