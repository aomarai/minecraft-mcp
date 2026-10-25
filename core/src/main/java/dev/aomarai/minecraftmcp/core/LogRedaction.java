package dev.aomarai.minecraftmcp.core;

import java.util.regex.Pattern;

/** Makes one log line safe to show: IP addresses are masked and very long lines are cut. */
final class LogRedaction {

    static final int MAX_LINE = 400;
    static final String IP = "[ip]";

    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");

    private LogRedaction() {
    }

    static String redact(String line) {
        String masked = IPV4.matcher(line).replaceAll(IP);
        return masked.length() > MAX_LINE ? masked.substring(0, MAX_LINE) + "..." : masked;
    }
}
