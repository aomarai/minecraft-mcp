package dev.aomarai.minecraftmcp.rcon;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The server's own operator list, {@code ops.json}, so that "is an operator" comes from the server, not a guess. */
final class OpList {

    private static final TypeRef<List<Map<String, Object>>> ENTRIES = new TypeRef<>() {
    };

    private OpList() {
    }

    /**
     * The operators' names in lower case (names are case-insensitive in the game). A server with no
     * {@code ops.json} yet has no operators.
     */
    static Set<String> names(Path opsFile) throws IOException {
        if (!Files.isRegularFile(opsFile)) {
            return Set.of();
        }
        List<Map<String, Object>> entries = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(opsFile), ENTRIES);
        Set<String> names = new HashSet<>();
        for (Map<String, Object> entry : entries) {
            if (entry.get("name") instanceof String name) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
