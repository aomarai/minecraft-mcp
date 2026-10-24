package dev.aomarai.minecraftmcp.core;

import java.util.List;
import java.util.Objects;

/** Result of a command or tool action. */
public record CommandResult(boolean success, String output, List<String> feedback) {

    public CommandResult {
        output = output == null ? "" : output;
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
    }

    public static CommandResult ok(String output) {
        return new CommandResult(true, output, List.of());
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, "", List.of(Objects.requireNonNullElse(message, "unknown error")));
    }
}
