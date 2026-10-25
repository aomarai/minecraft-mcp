package dev.aomarai.minecraftmcp.fabric;

import com.mojang.authlib.GameProfile;
import dev.aomarai.minecraftmcp.core.MainThread;
import dev.aomarai.minecraftmcp.core.PermissionReader;
import dev.aomarai.minecraftmcp.core.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;

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
final class FabricPermissionReader implements PermissionReader {

    private static final String UNKNOWN_UUID = "unknown";

    private final MinecraftServer server;

    FabricPermissionReader(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Permissions> permissions() {
        return MainThread.call(server, this::describe);
    }

    private Permissions describe() {
        PlayerList players = server.getPlayerList();
        return new Permissions(operators(players), players.isUsingWhitelist(), whitelist(players));
    }

    private List<Permissions.Operator> operators(PlayerList players) {
        // The names and the entries both come from the same map, so they line up one to one.
        String[] names = players.getOpNames();
        List<ServerOpListEntry> entries = new ArrayList<>(players.getOps().getEntries());
        List<Permissions.Operator> operators = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            operators.add(new Permissions.Operator(names[i], uuidOf(names[i]), entries.get(i).getLevel()));
        }
        operators.sort(Comparator.comparing(Permissions.Operator::name));
        return operators;
    }

    private List<Permissions.Whitelisted> whitelist(PlayerList players) {
        List<Permissions.Whitelisted> whitelisted = new ArrayList<>();
        for (String name : players.getWhiteListNames()) {
            whitelisted.add(new Permissions.Whitelisted(name, uuidOf(name)));
        }
        whitelisted.sort(Comparator.comparing(Permissions.Whitelisted::name));
        return whitelisted;
    }

    private String uuidOf(String name) {
        return server.getProfileCache().get(name).map(GameProfile::getId).map(Object::toString).orElse(UNKNOWN_UUID);
    }
}
