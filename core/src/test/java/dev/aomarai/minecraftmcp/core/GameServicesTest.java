package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameServicesTest {

    private final FakeGame game = new FakeGame();

    @Test
    void noneHasNoPortsAndNoCapabilitiesAndIsServerSide() {
        GameServices none = GameServices.none();

        assertEquals(Side.SERVER, none.side());
        assertEquals(Set.of(), none.capabilities());
        for (Capability capability : Capability.values()) {
            assertTrue(none.find(capability.portType()).isEmpty(), capability.name());
        }
    }

    @Test
    void oneObjectCanProvideEveryPort() {
        GameServices all = game.services();

        // FakeGame is a server-side game: it provides every capability except the client-only one.
        Set<Capability> serverCapabilities = new java.util.HashSet<>(Set.of(Capability.values()));
        serverCapabilities.remove(Capability.SCREENSHOTS);
        assertEquals(serverCapabilities, all.capabilities());
        assertSame(game, all.find(RuntimeInfo.class).orElseThrow());
        assertSame(game, all.find(RegistryReader.class).orElseThrow());
        assertSame(game, all.find(EntityReader.class).orElseThrow());
    }

    @Test
    void eachCapabilityIsReportedOnlyWhenItsPortIsPresent() {
        for (Capability capability : Capability.values()) {
            GameServices only = game.services(Side.SERVER, capability);

            assertEquals(Set.of(capability), only.capabilities(), capability.name());
            assertTrue(only.find(capability.portType()).isPresent(), capability.name());
        }
    }

    @Test
    void theSideIsKeptAndRequired() {
        assertEquals(Side.CLIENT, new GameServices(Side.CLIENT).side());
        assertThrows(NullPointerException.class, () -> new GameServices(null));
    }

    @Test
    void portsAreFoundByTheirTypeAndNotMistakenForOthers() {
        GameServices infoOnly = game.services(Side.SERVER, Capability.RUNTIME_INFO);

        assertTrue(infoOnly.find(RuntimeInfo.class).isPresent());
        assertTrue(infoOnly.find(PlayerReader.class).isEmpty());
    }

    @Test
    void everyCapabilityMapsToADistinctPortType() {
        assertEquals(Capability.values().length,
                java.util.Arrays.stream(Capability.values()).map(Capability::portType).distinct().count());
        assertEquals(ScreenshotCapture.class, Capability.SCREENSHOTS.portType());
        assertEquals(RegistryReader.class, Capability.REGISTRIES.portType());
        assertEquals(EntityReader.class, Capability.ENTITIES.portType());
    }
}
