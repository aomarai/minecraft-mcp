package dev.aomarai.minecraftmcp.core;

/** Describes the game runtime the adapter is attached to. */
public interface RuntimeInfo extends GamePort {

    /** Human-readable runtime description, e.g. "Fabric 0.19.5 / Minecraft 26.2 / server". */
    String describe();
}
