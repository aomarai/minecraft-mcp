package dev.aomarai.minecraftmcp.core;

import java.util.List;
import java.util.Objects;

/**
 * Result of a command or tool action. The output is cut at {@value #MAX_OUTPUT} characters, with a note saying so, so
 * that a command with a huge answer (help on a modded server, data get on a big block entity) cannot produce a
 * tool result of any size. Adapters may stop collecting once they hold that much.
 */
public record CommandResult(boolean success, String output, List<String> feedback) {

    public static final int MAX_OUTPUT = 16_384;
    static final String TRUNCATED = "\u2026 (truncated at " + MAX_OUTPUT + " characters)";

    public CommandResult {
        output = cap(output == null ? "" : output);
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
    }

    private static String cap(String output) {
        if (output.length() <= MAX_OUTPUT) {
            return output;
        }
        // Never end on half of a surrogate pair.
        int end = Character.isHighSurrogate(output.charAt(MAX_OUTPUT - 1)) ? MAX_OUTPUT - 1 : MAX_OUTPUT;
        return output.substring(0, end) + TRUNCATED;
    }

    public static CommandResult ok(String output) {
        return new CommandResult(true, output, List.of());
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, "", List.of(Objects.requireNonNullElse(message, "unknown error")));
    }
}
