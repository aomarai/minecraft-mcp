package dev.aomarai.minecraftmcp.vanilla;

import dev.aomarai.minecraftmcp.core.MainThread;
import dev.aomarai.minecraftmcp.core.PermissionReader;
import dev.aomarai.minecraftmcp.core.Permissions;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The server's own operator list and whitelist, read on the main thread. It grants the AI nothing.
 *
 * <p>The game lists names; the UUID comes from the server's profile cache and is {@code unknown}
 * for someone it has never seen.</p>
 */
public final class VanillaPermissionReader implements PermissionReader {

    private static final String UNKNOWN_UUID = "unknown";

    private final MinecraftServer server;

    public VanillaPermissionReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Permissions> permissions() {
        return MainThread.call(server, this::describe);
    }

    private Permissions describe() {
        return new Permissions(operators(), GameCompat.usingWhitelist(server), whitelist());
    }

    private List<Permissions.Operator> operators() {
        List<Permissions.Operator> operators = new ArrayList<>();
        for (GameCompat.Operator operator : GameCompat.operators(server)) {
            operators.add(new Permissions.Operator(operator.name(), operator.uuid().orElse(UNKNOWN_UUID), operator.level()));
        }
        operators.sort(Comparator.comparing(Permissions.Operator::name));
        return operators;
    }

    private List<Permissions.Whitelisted> whitelist() {
        List<Permissions.Whitelisted> whitelisted = new ArrayList<>();
        for (String name : GameCompat.whitelistedNames(server)) {
            whitelisted.add(new Permissions.Whitelisted(name, uuidOf(name)));
        }
        whitelisted.sort(Comparator.comparing(Permissions.Whitelisted::name));
        return whitelisted;
    }

    private String uuidOf(String name) {
        return GameCompat.uuidOfName(server, name).orElse(UNKNOWN_UUID);
    }
}
