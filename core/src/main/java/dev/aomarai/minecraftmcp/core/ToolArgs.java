package dev.aomarai.minecraftmcp.core;

import java.util.Map;

/**
 * Reads the arguments of one tool call, with the type checks every handler needs. Anything of the
 * wrong type is an {@link IllegalArgumentException} naming the argument, which the dispatcher turns
 * into an error result. Numbers must be exact: {@code 1.5} is not an integer, and an integer that
 * does not fit an {@code int} is rejected rather than wrapped.
 */
record ToolArgs(Map<String, Object> values) {

    String string(String name) {
        if (!(values.get(name) instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return text;
    }

    String optionalString(String name, String fallback) {
        return values.get(name) == null ? fallback : string(name);
    }

    boolean optionalBoolean(String name, boolean fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return flag;
    }

    int integer(String name) {
        if (!(values.get(name) instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return number.intValue();
    }

    int optionalInteger(String name, int fallback) {
        return values.get(name) == null ? fallback : integer(name);
    }

    double number(String name) {
        if (!(values.get(name) instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return number.doubleValue();
    }
}
