package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.CommandResult;
import dev.aomarai.minecraftmcp.core.CommandRunner;
import dev.aomarai.minecraftmcp.core.PlayerInfo;
import dev.aomarai.minecraftmcp.core.PlayerReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Lists the players of a server that has no mod: the names come from the Query protocol, where each
 * player is and which dimension they are in from the vanilla {@code data get entity} command (so modded
 * dimensions work), and who is an operator from the server's own {@code ops.json}.
 *
 * <p>A name from the network is untrusted, and it is put into a command, so only a real player name
 * (letters, digits and underscores, at most 16) is ever used; anything else is skipped. A player who leaves
 * while the list is being built is skipped too. At most {@value #MAX_PLAYERS} players are listed.</p>
 */
final class UniversalPlayerReader implements PlayerReader {

    /** The names of the players online now. */
    @FunctionalInterface
    interface OnlinePlayers {
        List<String> names() throws IOException;
    }

    static final int MAX_PLAYERS = 100;
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final OnlinePlayers online;
    private final CommandRunner commands;
    private final Path opsFile;
    private final Duration timeout;
    private final Executor worker;

    UniversalPlayerReader(OnlinePlayers online, CommandRunner commands, Path opsFile, Duration timeout, Executor worker) {
        this.online = online;
        this.commands = commands;
        this.opsFile = opsFile;
        this.timeout = timeout;
        this.worker = worker;
    }

    @Override
    public CompletableFuture<List<PlayerInfo>> listPlayers() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return list();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, worker);
    }

    private List<PlayerInfo> list() throws IOException {
        Set<String> operators = OpList.names(opsFile);
        List<PlayerInfo> players = new ArrayList<>();
        for (String name : online.names()) {
            if (players.size() == MAX_PLAYERS) {
                break;
            }
            if (PLAYER_NAME.matcher(name).matches()) {
                describe(name, operators.contains(name.toLowerCase(Locale.ROOT))).ifPresent(players::add);
            }
        }
        return players;
    }

    /** Empty when the player is gone by the time the game is asked. */
    private java.util.Optional<PlayerInfo> describe(String name, boolean operator) throws IOException {
        CommandResult position = run("data get entity " + name + " Pos");
        CommandResult dimension = run("data get entity " + name + " Dimension");
        CommandResult uuid = run("data get entity " + name + " UUID");
        if (!position.success() || !dimension.success() || !uuid.success()) {
            return java.util.Optional.empty();
        }
        double[] at = EntityData.position(position.output());
        return java.util.Optional.of(new PlayerInfo(name, EntityData.uuid(uuid.output()),
                EntityData.dimension(dimension.output()), at[0], at[1], at[2], operator));
    }

    private CommandResult run(String command) throws IOException {
        try {
            return commands.runCommand(command, timeout).get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking the server");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("the server did not answer '" + command + "'");
        }
    }
}
