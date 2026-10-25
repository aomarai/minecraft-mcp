package dev.aomarai.minecraftmcp.core;

/** The configuration file or environment is unusable. The message names the offending key. */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
