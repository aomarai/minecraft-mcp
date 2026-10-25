package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Golden-file check for {@code tools/list}. Any change to a tool name, schema
 * or annotation changes the output and fails this test. If the change is
 * intended, regenerate with {@code GOLDEN_UPDATE=true ./gradlew :core:test}
 * and review the diff of {@code tools-list.json}.
 */
class ToolCatalogGoldenTest {

    private static final String RESOURCE = "/golden/tools-list.json";
    private static final Path SOURCE = Path.of("src/test/resources/golden/tools-list.json");

    @Test
    void toolsListMatchesGoldenFile() throws Exception {
        String actual = render(fetchToolsList());

        if ("true".equals(System.getenv("GOLDEN_UPDATE"))) {
            Files.createDirectories(SOURCE.getParent());
            Files.writeString(SOURCE, actual, StandardCharsets.UTF_8);
        }

        try (InputStream in = ToolCatalogGoldenTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "missing " + RESOURCE + "; regenerate with GOLDEN_UPDATE=true");
            String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(normalize(expected), normalize(actual),
                    "tools/list changed; if intended, regenerate with GOLDEN_UPDATE=true and review the diff");
        }
    }

    @Test
    void rendererIsCanonical() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("b", List.of(1, true, "x\"y\n\\"));
        input.put("a", Map.of());
        input.put("c", List.of());
        input.put("d", null);
        assertEquals("{\n"
                + "  \"a\": {},\n"
                + "  \"b\": [\n"
                + "    1,\n"
                + "    true,\n"
                + "    \"x\\\"y\\n\\\\\"\n"
                + "  ],\n"
                + "  \"c\": [],\n"
                + "  \"d\": null\n"
                + "}\n", render(input));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static List<Object> fetchToolsList() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + core.port())
                    .endpoint(McpCore.DEFAULT_ENDPOINT)
                    .build();
            try (McpSyncClient client = McpClient.sync(transport).build()) {
                client.initialize();
                List<Object> tools = new ArrayList<>();
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    tools.add(McpJsonDefaults.getMapper().convertValue(tool, new TypeRef<Map<String, Object>>() {
                    }));
                }
                return tools;
            }
        }
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
