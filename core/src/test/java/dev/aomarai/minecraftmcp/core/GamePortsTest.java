package dev.aomarai.minecraftmcp.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the port shape: ports are independent, an adapter can implement any
 * subset, and each one is a {@link GamePort}.
 */
class GamePortsTest {

    /** A read-only platform: it can describe itself and list players, nothing else. */
    private static final class ReadOnlyAdapter implements RuntimeInfo, PlayerReader {
        @Override
        public String describe() {
            return "fake / read-only";
        }

        @Override
        public CompletableFuture<List<PlayerInfo>> listPlayers() {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /** A full platform implements every port. */
    private static final class FullAdapter implements RuntimeInfo, PlayerReader, WorldReader, CommandRunner {
        @Override
        public String describe() {
            return "fake / full";
        }

        @Override
        public CompletableFuture<List<PlayerInfo>> listPlayers() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<WorldSnapshot> snapshot(BlockQuery query) {
            return CompletableFuture.completedFuture(new WorldSnapshot(query.dimension(), List.of(), false));
        }

        @Override
        public CompletableFuture<CommandResult> runCommand(String command, Duration timeout) {
            return CompletableFuture.completedFuture(CommandResult.ok(command));
        }
    }

    @Test
    void adapterMayImplementASubsetOfPorts() {
        Object adapter = new ReadOnlyAdapter();
        assertInstanceOf(GamePort.class, adapter);
        assertInstanceOf(RuntimeInfo.class, adapter);
        assertInstanceOf(PlayerReader.class, adapter);
        assertFalse(adapter instanceof WorldReader);
        assertFalse(adapter instanceof CommandRunner);
        ReadOnlyAdapter typed = (ReadOnlyAdapter) adapter;
        assertEquals("fake / read-only", typed.describe());
        assertTrue(typed.listPlayers().join().isEmpty());
    }

    @Test
    void fullAdapterServesEveryPort() {
        FullAdapter adapter = new FullAdapter();
        assertEquals("fake / full", adapter.describe());
        assertTrue(adapter.listPlayers().join().isEmpty());
        BlockQuery query = new BlockQuery("minecraft:overworld", 0, 0, 0, 0, 0, 0, 1);
        assertEquals("minecraft:overworld", adapter.snapshot(query).join().dimension());
        assertEquals("say hi", adapter.runCommand("say hi", Duration.ofSeconds(1)).join().output());
    }
}
