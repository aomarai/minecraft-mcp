package dev.aomarai.minecraftmcp.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version of this build, as the MCP {@code initialize} answer reports it. The build writes it into
 * {@code version.properties} next to this class, so every jar that carries the core (shaded or not) reads its own.
 * Without that file (an IDE run) the version is {@value #UNKNOWN}.
 */
final class McpVersion {

    static final String UNKNOWN = "dev";

    private McpVersion() {
    }

    static String current() {
        return read(McpVersion.class.getResourceAsStream("version.properties"));
    }

    /** The {@code version} in these properties, or {@value #UNKNOWN} when there is no stream, no key or no value. */
    static String read(InputStream in) {
        if (in == null) {
            return UNKNOWN;
        }
        try (in) {
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").trim();
            return version.isEmpty() ? UNKNOWN : version;
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
