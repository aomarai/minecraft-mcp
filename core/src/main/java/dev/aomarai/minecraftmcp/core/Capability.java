package dev.aomarai.minecraftmcp.core;

/** A capability an adapter may provide. Each one corresponds to one {@link GamePort} type. */
public enum Capability {
    RUNTIME_INFO(RuntimeInfo.class),
    PLAYERS(PlayerReader.class),
    WORLD(WorldReader.class),
    COMMANDS(CommandRunner.class),
    REGISTRIES(RegistryReader.class),
    ENTITIES(EntityReader.class),
    WORLD_STATE(WorldStateReader.class),
    PERMISSIONS(PermissionReader.class),
    INVENTORIES(InventoryReader.class),
    LOGS(LogReader.class),
    EVENTS(EventFeed.class),
    /** Client side only. */
    SCREENSHOTS(ScreenshotCapture.class),
    /** Client side only. */
    SCREENS(ScreenReader.class),
    /** Client side only. */
    SCREEN_CONTROL(ScreenControl.class);

    private final Class<? extends GamePort> portType;

    Capability(Class<? extends GamePort> portType) {
        this.portType = portType;
    }

    /** The port an adapter implements to provide this capability. */
    public Class<? extends GamePort> portType() {
        return portType;
    }
}
