package dev.aomarai.minecraftmcp.core;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The capability ports an adapter provides, and the side it runs on. One object may implement
 * several ports. {@link ToolCatalog#availableFor} leaves out the tools whose capability is missing,
 * so an adapter never has to stub a port it cannot serve.
 */
public final class GameServices {

    private final Side side;
    private final List<GamePort> ports;

    public GameServices(Side side, GamePort... ports) {
        this.side = Objects.requireNonNull(side, "side");
        this.ports = List.of(ports);
    }

    /** A server-side adapter with no capabilities. */
    public static GameServices none() {
        return new GameServices(Side.SERVER);
    }

    public Side side() {
        return side;
    }

    /** The port of this type, if the adapter provides one. */
    public <T extends GamePort> Optional<T> find(Class<T> type) {
        return ports.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    public Set<Capability> capabilities() {
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        for (Capability capability : Capability.values()) {
            if (find(capability.portType()).isPresent()) {
                capabilities.add(capability);
            }
        }
        return capabilities;
    }
}
