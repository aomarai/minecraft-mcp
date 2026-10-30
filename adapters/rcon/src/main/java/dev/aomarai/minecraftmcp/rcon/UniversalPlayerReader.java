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
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Lists the players of a server that has no mod: the names come from the Query protocol, where each
 * player is and which dimension they are in from the vanilla {@code data get entity} command (so modded
 * dimensions work), and who is an operator from the server's own {@code ops.json}.
 *
 * <p>A name from the network is untrusted, and it is put into a command, so only a real player name
 * (letters, digits and underscores, at most 16) is ever used; anything else is skipped. A player who leaves
 * while the list is being built is skipped too. At most {@value #MAX_PLAYERS} players are listed.</p>
 *
 * <p>The whole listing, not each command, gets the call's timeout, and it stops asking the server as soon as the
 * call is cancelled: a listing nobody waits for must not keep the single RCON thread busy for the commands of the
 * players still to come.</p>
 */
final class UniversalPlayerReader implements PlayerReader {

    private static final String DATA_GET_ENTITY = "data get entity ";

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
    private final LongSupplier nanos;

    UniversalPlayerReader(OnlinePlayers online, CommandRunner commands, Path opsFile, Duration timeout, Executor worker) {
        this(online, commands, opsFile, timeout, worker, System::nanoTime);
    }

    UniversalPlayerReader(OnlinePlayers online, CommandRunner commands, Path opsFile, Duration timeout, Executor worker,
                          LongSupplier nanos) {
        this.nanos = nanos;
        this.online = online;
        this.commands = commands;
        this.opsFile = opsFile;
        this.timeout = timeout;
        this.worker = worker;
    }

    @Override
    public CompletableFuture<List<PlayerInfo>> listPlayers() {
        CompletableFuture<List<PlayerInfo>> result = new CompletableFuture<>();
        // completeAsync does not start a listing that was cancelled while it waited its turn.
        return result.completeAsync(() -> {
            try {
                return list(result);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, worker);
    }

    /** Builds the list; {@code wanted} is checked before every command, so a cancelled listing asks the server nothing more. */
    private List<PlayerInfo> list(CompletableFuture<?> wanted) throws IOException {
        long deadline = nanos.getAsLong() + timeout.toNanos();
        Set<String> operators = OpList.names(opsFile);
        List<PlayerInfo> players = new ArrayList<>();
        for (String name : online.names()) {
            if (players.size() == MAX_PLAYERS) {
                break;
            }
            if (PLAYER_NAME.matcher(name).matches()) {
                describe(name, operators.contains(name.toLowerCase(Locale.ROOT)), wanted, deadline).ifPresent(players::add);
            }
        }
        return players;
    }

    /** Empty when the player is gone by the time the game is asked. */
    private java.util.Optional<PlayerInfo> describe(String name, boolean operator, CompletableFuture<?> wanted,
                                                    long deadline) throws IOException {
        CommandResult position = run(DATA_GET_ENTITY + name + " Pos", wanted, deadline);
        CommandResult dimension = run(DATA_GET_ENTITY + name + " Dimension", wanted, deadline);
        CommandResult uuid = run(DATA_GET_ENTITY + name + " UUID", wanted, deadline);
        if (!position.success() || !dimension.success() || !uuid.success()) {
            return java.util.Optional.empty();
        }
        double[] at = EntityData.position(position.output());
        return java.util.Optional.of(new PlayerInfo(name, EntityData.uuid(uuid.output()),
                EntityData.dimension(dimension.output()), at[0], at[1], at[2], operator));
    }

    /** One command, within what is left of the listing's timeout; once the call is cancelled, nothing more is asked. */
    private CommandResult run(String command, CompletableFuture<?> wanted, long deadline) throws IOException {
        long left = deadline - nanos.getAsLong();
        if (wanted.isCancelled() || left <= 0) {
            throw new IOException("stopped listing the players: the call was cancelled or took longer than "
                    + timeout.toMillis() + " ms");
        }
        try {
            return commands.runCommand(command, Duration.ofNanos(left))
                    .get(TimeUnit.NANOSECONDS.toMillis(left) + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking the server");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("the server did not answer '" + command + "'");
        }
    }
}
