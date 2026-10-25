package dev.aomarai.minecraftmcp.rcon;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Where the game server's RCON port is, and the password for it.
 *
 * <p>RCON is plain text: the password and every command cross the network unencrypted. So the host
 * must be this machine unless {@code allowRemote} says otherwise, on purpose.</p>
 *
 * @param password a secret; never part of {@link #toString()} or of any error message
 */
public record RconConfig(String host, int port, String password, boolean allowRemote) {

    public RconConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty (the game requires rcon.password)");
        }
        if (!allowRemote && !isLoopback(host)) {
            throw new IllegalArgumentException("host '" + host + "' is not this machine; RCON is not encrypted, "
                    + "so a remote host needs allowRemote to be set explicitly");
        }
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("host '" + host + "' cannot be resolved");
        }
    }

    @Override
    public String toString() {
        return "RconConfig[host=" + host + ", port=" + port + ", password=<hidden>, allowRemote=" + allowRemote + "]";
    }
}
