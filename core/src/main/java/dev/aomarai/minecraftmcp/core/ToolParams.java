package dev.aomarai.minecraftmcp.core;

/**
 * The names of the arguments the tools take. The catalog (the schema a client sees) and the handlers (which read
 * the arguments) both use these, so a typo in one cannot produce a name the other silently ignores.
 */
final class ToolParams {

    static final String DIMENSION = "dimension";
    static final String MIN_X = "min_x";
    static final String MIN_Y = "min_y";
    static final String MIN_Z = "min_z";
    static final String MAX_X = "max_x";
    static final String MAX_Y = "max_y";
    static final String MAX_Z = "max_z";
    static final String LIMIT = "limit";
    static final String PLAYER = "player";
    static final String CONFIRM = "confirm";
    static final String ELEMENT = "element";
    static final String COMMAND = "command";
    static final String ITEM = "item";
    static final String COUNT = "count";
    static final String REASON = "reason";
    static final String MESSAGE = "message";
    static final String ROLE = "role";
    static final String LABEL = "label";
    static final String AMOUNT = "amount";
    static final String OFFSET = "offset";
    static final String RADIUS = "radius";
    static final String LINES = "lines";
    static final String BLOCK = "block";
    static final String REGISTRY = "registry";
    static final String TEXT = "text";

    private ToolParams() {
    }
}
