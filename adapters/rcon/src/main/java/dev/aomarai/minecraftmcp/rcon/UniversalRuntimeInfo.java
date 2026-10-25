package dev.aomarai.minecraftmcp.rcon;

import dev.aomarai.minecraftmcp.core.RuntimeInfo;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Says that this is the universal (RCON) tier, and which Minecraft version the server reports when the
 * Query protocol is available. The version is asked for on demand, kept once known, and not asked for again
 * for {@value #RETRY_SECONDS} seconds after a failed attempt, so a server with Query off costs one short
 * wait now and then, not one per call.
 */
final class UniversalRuntimeInfo implements RuntimeInfo {

    /** Asks the server for its version. */
    @FunctionalInterface
    interface VersionSource {
        String version() throws IOException;
    }

    static final int RETRY_SECONDS = 30;
    /** What a version looks like: the server's own text is shown to the model, so only this much of it is. */
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._ -]{1,32}");

    private final VersionSource source;
    private final Clock clock;
    private String known;
    private Instant retryAfter = Instant.MIN;

    UniversalRuntimeInfo(VersionSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public synchronized String describe() {
        return "universal/RCON / Minecraft " + version() + " / server";
    }

    private String version() {
        if (known != null) {
            return known;
        }
        Instant now = clock.instant();
        if (now.isBefore(retryAfter)) {
            return "unknown";
        }
        try {
            String reported = source.version();
            if (VERSION.matcher(reported).matches()) {
                known = reported;
                return known;
            }
        } catch (IOException e) {
            // Query is off or unreachable: carry on without a version.
        }
        retryAfter = now.plus(Duration.ofSeconds(RETRY_SECONDS));
        return "unknown";
    }
}
