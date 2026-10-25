package dev.aomarai.minecraftmcp.core;

/**
 * Marker for a capability port between the loader-agnostic MCP core and a
 * Minecraft runtime. Each port covers one concern ({@link CommandRunner},
 * {@link PlayerReader}, {@link WorldReader}, {@link RuntimeInfo}); an adapter
 * implements only the ports its platform can serve, and a new tool group adds
 * a port instead of a method on a shared interface.
 *
 * <p>Threading contract for every port: implementations must marshal every
 * world read and write onto the game's main thread, because the core calls
 * them from HTTP worker threads. Calls must honor their timeout and be
 * cancellable without leaving partial world writes. No port may block the
 * main thread waiting for an MCP client.</p>
 */
public interface GamePort {
}
