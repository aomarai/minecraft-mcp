package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Golden files for {@code tools/list}: one file per tool, so adding or changing
 * a tool changes exactly one file. Each file holds the descriptor as a client
 * sees it, plus the capability and side the catalog declares for it.
 *
 * <p>Any change to a tool name, schema, annotation, capability or side fails
 * this test. If the change is intended, regenerate with
 * {@code GOLDEN_UPDATE=true ./gradlew :core:test} and review the diff under
 * {@code core/src/test/resources/golden/tools/}.</p>
 */
class ToolCatalogGoldenTest {

    private static final String RESOURCE_DIR = "/golden/tools/";
    private static final Path SOURCE_DIR = Path.of("src/test/resources/golden/tools");

    @Test
    void everyToolMatchesItsGoldenFile() throws Exception {
        Map<String, String> actual = renderEachTool();

        if ("true".equals(System.getenv("GOLDEN_UPDATE"))) {
            regenerate(actual);
            return;
        }

        for (Map.Entry<String, String> tool : actual.entrySet()) {
            try (InputStream in = ToolCatalogGoldenTest.class
                    .getResourceAsStream(RESOURCE_DIR + tool.getKey() + ".json")) {
                assertNotNull(in, "missing golden file for " + tool.getKey() + "; regenerate with GOLDEN_UPDATE=true");
                String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(normalize(expected), normalize(tool.getValue()),
                        tool.getKey() + " changed; if intended, regenerate with GOLDEN_UPDATE=true and review the diff");
            }
        }
    }

    @Test
    void noGoldenFileExistsForAToolThatIsGone() throws Exception {
        List<String> files;
        try (Stream<Path> listing = Files.list(SOURCE_DIR)) {
            files = listing.map(p -> p.getFileName().toString()).sorted().toList();
        }
        List<String> expected = ToolCatalog.defaults().tools().stream()
                .map(tool -> tool.name() + ".json").sorted().toList();
        assertEquals(expected, files, "stale or missing golden files; regenerate with GOLDEN_UPDATE=true");
    }

    @Test
    void rendererIsCanonical() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("b", List.of(1, true, "x\"y\n\\"));
        input.put("a", Map.of());
        input.put("c", List.of());
        input.put("d", null);
        assertEquals("""
                {
                  "a": {},
                  "b": [
                    1,
                    true,
                    "x\\"y\\n\\\\"
                  ],
                  "c": [],
                  "d": null
                }
                """, render(input));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static void regenerate(Map<String, String> actual) throws IOException {
        Files.createDirectories(SOURCE_DIR);
        try (Stream<Path> existing = Files.list(SOURCE_DIR)) {
            for (Path file : existing.toList()) {
                String name = file.getFileName().toString();
                if (!actual.containsKey(name.substring(0, name.length() - ".json".length()))) {
                    Files.delete(file);
                }
            }
        }
        for (Map.Entry<String, String> tool : actual.entrySet()) {
            Files.writeString(SOURCE_DIR.resolve(tool.getKey() + ".json"), tool.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** Fetches {@code tools/list} from a real server and renders one canonical document per tool. */
    private static Map<String, String> renderEachTool() throws Exception {
        Map<String, ToolDescriptor> declared = new LinkedHashMap<>();
        ToolCatalog.defaults().tools().forEach(tool -> declared.put(tool.name(), tool));

        Map<String, String> rendered = new LinkedHashMap<>();
        try (McpCore core = TestCores.full(new FakeGame())) {
            core.start();
            try (McpSyncClient client = McpTestClients.client(core, TestCores.TOKEN)) {
                client.initialize();
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    rendered.put(tool.name(), render(document(declared.get(tool.name()), tool)));
                }
            }
        }
        // Tools that run on the client are not served by this server, so render them from their descriptors.
        for (ToolDescriptor descriptor : declared.values()) {
            rendered.computeIfAbsent(descriptor.name(), name -> render(document(descriptor, descriptor.toTool())));
        }
        return rendered;
    }

    private static Map<String, Object> document(ToolDescriptor descriptor, McpSchema.Tool tool) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("capability", descriptor.capability().name());
        document.put("side", descriptor.side().name());
        document.put("tool", McpJsonDefaults.getMapper().convertValue(tool, new TypeRef<Map<String, Object>>() {
        }));
        return document;
    }

    /** Pretty-prints with sorted keys so the golden file is independent of map ordering. */
    static String render(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) map).entrySet()) {
                indent(out, depth + 1);
                quote(out, e.getKey());
                out.append(": ");
                write(out, e.getValue(), depth + 1);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(out, depth + 1);
                write(out, list.get(i), depth + 1);
                out.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else if (value instanceof String s) {
            quote(out, s);
        } else {
            out.append(value);
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"')
                .append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                .append('"');
    }
}
