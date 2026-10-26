package dev.aomarai.minecraftmcp.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where the endpoint gets its certificate when it serves HTTPS itself: a PKCS12 keystore file and its password.
 * The password is a secret and stays out of {@link #toString()}.
 */
public record Tls(Path keystore, String password) {

    public Tls {
        Objects.requireNonNull(keystore, "keystore");
        Objects.requireNonNull(password, "password");
        if (password.isEmpty()) {
            throw new IllegalArgumentException("tlsKeystorePassword must not be empty");
        }
    }

    @Override
    public String toString() {
        return "Tls[keystore=" + keystore + ", password=<redacted>]";
    }
}
