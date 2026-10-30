package dev.aomarai.minecraftmcp.core;

import java.util.regex.Pattern;

/** Makes one log line safe to show: IPv4 and IPv6 addresses are masked and very long lines are cut. */
final class LogRedaction {

    static final int MAX_LINE = 400;
    static final String IP = "[ip]";

    private static final Pattern IPV4 = Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?\\b");

    // Eight groups, or a "::" gap between at most seven; the last group may be a dotted IPv4 (::ffff:1.2.3.4).
    // Without one of those shapes it is not an address, so "12:34:56" timestamps stay readable.
    private static final String HEX = "[0-9a-fA-F]{1,4}";
    private static final String TAIL = "(?:" + HEX + "|[0-9]{1,3}(?:\\.[0-9]{1,3}){3})";
    private static final String V6 = "(?:(?:" + HEX + ":){7}" + TAIL
            + "|(?:" + HEX + "(?::" + HEX + "){0,6})?::(?:" + HEX + "(?::" + HEX + "){0,5}:)?" + TAIL + ")";
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![\\w:.])(?:\\[" + V6 + "\\](?::[0-9]{1,5})?|" + V6 + ")(?![\\w:.])");

    private LogRedaction() {
    }

    static String redact(String line) {
        String masked = IPV4.matcher(IPV6.matcher(line).replaceAll(IP)).replaceAll(IP);
        return masked.length() > MAX_LINE ? masked.substring(0, MAX_LINE) + "..." : masked;
    }
}
