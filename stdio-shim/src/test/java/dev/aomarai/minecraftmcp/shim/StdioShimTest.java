package dev.aomarai.minecraftmcp.shim;

import com.sun.net.httpserver.HttpServer;
import dev.aomarai.minecraftmcp.core.McpCore;
import dev.aomarai.minecraftmcp.core.ToolCatalog;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StdioShimTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"host\",\"version\":\"1\"}}}";
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"server_info\",\"arguments\":{}}}";

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(err, true, StandardCharsets.UTF_8);
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    /** What the stub server saw. */
    private record Seen(String method, String authorization, String sessionId, String body) {
    }

    private interface Reply {
        void send(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private HttpServer stub(List<Seen> seen, Reply reply) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Mcp-Session-Id"), body));
            reply.send(exchange);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return server;
    }

    private static Reply answer(int status, String contentType, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        };
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private StdioShim shim(String url, Duration timeout) {
        return new StdioShim(URI.create(url), TOKEN, timeout, log);
    }

    private List<String> run(StdioShim shim, String... lines) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // No auto-flush and an extra buffer: output only reaches `out` if the shim flushes it.
        shim.pump(new BufferedReader(new StringReader(String.join("\n", lines) + "\n")),
                new PrintStream(new BufferedOutputStream(out), false, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        return text.isEmpty() ? List.of() : List.of(text.split("\n"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String line) throws IOException {
        return (Map<String, Object>) McpJsonDefaults.getMapper().readValue(line, Object.class);
    }

    private static Map<String, Object> errorOf(String line) throws IOException {
        Map<String, Object> message = json(line);
        assertEquals("2.0", message.get("jsonrpc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) message.get("error");
        return error;
    }

    // ---- end to end against the real core server ----

    @Test
    void aHostCanInitializeListToolsAndCallOneThroughTheShim() throws Exception {
        ToolCatalog catalog = ToolCatalog.defaults();
        try (McpCore core = new McpCore("127.0.0.1", 0, catalog, (tool, args) -> McpSchema.CallToolResult.builder()
                .addTextContent("called " + tool.name()).build(), TOKEN)) {
            core.start();
            StdioShim shim = shim("http://127.0.0.1:" + core.port() + "/mcp", Duration.ofSeconds(10));

            List<String> out = run(shim, INIT, INITIALIZED, LIST, CALL);

            assertEquals(3, out.size(), "the notification produces no output: " + out);
            Map<String, Object> init = json(out.get(0));
            assertEquals(1, init.get("id"));
            assertNotNull(init.get("result"));
            Map<String, Object> list = json(out.get(1));
            assertEquals(2, list.get("id"));
            assertTrue(out.get(1).contains("\"server_info\""), out.get(1));
            assertEquals(5, ((List<?>) ((Map<?, ?>) list.get("result")).get("tools")).size());
            Map<String, Object> call = json(out.get(2));
            assertEquals(3, call.get("id"));
            assertTrue(out.get(2).contains("called server_info"), out.get(2));
            for (String line : out) {
                assertFalse(line.contains("\n"));
                assertFalse(line.contains(TOKEN));
            }
            assertFalse(err.toString(StandardCharsets.UTF_8).contains(TOKEN));
        }
    }

    @Test
    void aWrongTokenIsAClearErrorForThatRequestAndNeverEchoesTheToken() throws Exception {
        try (McpCore core = new McpCore("127.0.0.1", 0)) {
            core.start();
            StdioShim wrong = new StdioShim(URI.create("http://127.0.0.1:" + core.port() + "/mcp"),
                    "not-the-token-not-the-token-1234", Duration.ofSeconds(10), log);

            List<String> out = run(wrong, INIT);

            assertEquals(1, out.size());
            assertEquals(1, json(out.get(0)).get("id"));
            Map<String, Object> error = errorOf(out.get(0));
            assertEquals(StdioShim.SERVER_ERROR, error.get("code"));
            assertEquals("the MCP server rejected the token (HTTP 401): check MINECRAFT_MCP_TOKEN", error.get("message"));
            assertFalse(out.get(0).contains("not-the-token"));
            assertFalse(err.toString(StandardCharsets.UTF_8).contains("not-the-token"));
        }
    }

    // ---- connection failures never hang ----

    @Test
    void anUnreachableServerIsAnErrorNotAHang() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        long start = System.nanoTime();

        List<String> out = run(shim("http://127.0.0.1:" + closedPort + "/mcp", Duration.ofSeconds(5)), LIST);

        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
        Map<String, Object> error = errorOf(out.get(0));
        assertEquals(2, json(out.get(0)).get("id"));
        assertTrue(error.get("message").toString()
                .startsWith("cannot reach the MCP server at http://127.0.0.1:" + closedPort + "/mcp: "),
                error.get("message").toString());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot reach the MCP server"));
    }

    @Test
    void aServerThatNeverAnswersTimesOut() throws Exception {
        HttpServer slow = stub(new CopyOnWriteArrayList<>(), exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long start = System.nanoTime();
        List<String> out = run(shim(url(slow), Duration.ofMillis(300)), LIST);

        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_500);
        assertEquals(2, json(out.get(0)).get("id"));
        assertTrue(errorOf(out.get(0)).get("message").toString().contains("cannot reach the MCP server"));
    }

    @Test
    void interruptionIsReportedAndTheFlagRestored() throws Exception {
        HttpServer slow = stub(new CopyOnWriteArrayList<>(), exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        StdioShim shim = shim(url(slow), Duration.ofSeconds(30));
        AtomicReference<List<String>> out = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                out.set(run(shim, LIST));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive());
        assertEquals("interrupted while waiting for the MCP server", errorOf(out.get().get(0)).get("message"));
        assertEquals(Boolean.TRUE, flag.get());
    }

    // ---- HTTP status mapping ----

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 503})
    void errorStatusesBecomeErrorsForTheRequestId(int status) throws Exception {
        HttpServer server = stub(new CopyOnWriteArrayList<>(), answer(status, null, ""));

        List<String> out = run(shim(url(server), Duration.ofSeconds(5)), LIST);

        assertEquals(2, json(out.get(0)).get("id"));
        String message = errorOf(out.get(0)).get("message").toString();
        switch (status) {
            case 401 -> assertEquals("the MCP server rejected the token (HTTP 401): check MINECRAFT_MCP_TOKEN", message);
            case 403 -> assertEquals("the MCP server refused the request (HTTP 403): check the URL host and port", message);
            case 404 -> assertEquals("no MCP endpoint at that URL (HTTP 404): check MINECRAFT_MCP_URL", message);
            default -> assertEquals("the MCP server answered HTTP " + status, message);
        }
    }

    @Test
    void anErrorStatusOnANotificationGoesToTheLogOnlyWithANullId() throws Exception {
        HttpServer server = stub(new CopyOnWriteArrayList<>(), answer(500, null, ""));
        List<String> out = run(shim(url(server), Duration.ofSeconds(5)), INITIALIZED);
        assertEquals(1, out.size());
        assertNull(json(out.get(0)).get("id"));
    }

    @ParameterizedTest
    @ValueSource(ints = {202, 204})
    void acceptedAndNoContentProduceNoOutput(int status) throws Exception {
        HttpServer server = stub(new CopyOnWriteArrayList<>(), answer(status, null, ""));
        assertEquals(List.of(), run(shim(url(server), Duration.ofSeconds(5)), INITIALIZED));
    }

    @Test
    void connectionProblemsNameTheCause() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String refused = errorOf(run(shim("http://127.0.0.1:" + closedPort + "/mcp", Duration.ofSeconds(5)), LIST)
                .get(0)).get("message").toString();
        assertTrue(refused.endsWith(": ConnectException"), refused);

        HttpServer slow = stub(new CopyOnWriteArrayList<>(), exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        String timedOut = errorOf(run(shim(url(slow), Duration.ofMillis(200)), LIST).get(0)).get("message").toString();
        assertTrue(timedOut.endsWith(": request timed out"), timedOut);
    }

    // ---- response relaying ----

    @Test
    void jsonResponsesAreRelayedAsOneCompactLine() throws Exception {
        HttpServer server = stub(new CopyOnWriteArrayList<>(),
                answer(200, "application/json", "{\n  \"jsonrpc\": \"2.0\",\n  \"id\": 2,\n  \"result\": {}\n}\n"));
        assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"),
                run(shim(url(server), Duration.ofSeconds(5)), LIST));
    }

    @Test
    void aResponseWithoutAContentTypeIsTreatedAsJson() throws Exception {
        HttpServer server = stub(new CopyOnWriteArrayList<>(), answer(200, null, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"));
        assertEquals(1, run(shim(url(server), Duration.ofSeconds(5)), LIST).size());
    }

    @Test
    void serverSentEventsAreRelayedOneMessagePerEvent() throws Exception {
        String sse = ": keep-alive\n"
                + "id: 1\n"
                + "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n"
                + "\n"
                + "\n"
                + "data: {\"jsonrpc\":\"2.0\",\n"
                + "data: \"id\":2,\"result\":{}}\n"
                + "\n"
                + "data:{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}\n";
        HttpServer server = stub(new CopyOnWriteArrayList<>(), answer(200, "Text/Event-Stream; charset=utf-8", sse));

        List<String> out = run(shim(url(server), Duration.ofSeconds(5)), LIST);

        assertEquals(List.of(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}"), out);
    }

    @Test
    void invalidJsonFromTheServerIsAnErrorForTheRequest() throws Exception {
        HttpServer json = stub(new CopyOnWriteArrayList<>(), answer(200, "application/json", "not json"));
        List<String> out = run(shim(url(json), Duration.ofSeconds(5)), LIST);
        assertEquals(2, json(out.get(0)).get("id"));
        assertEquals("the MCP server sent a response that is not valid JSON", errorOf(out.get(0)).get("message"));

        HttpServer sse = stub(new CopyOnWriteArrayList<>(), answer(200, "text/event-stream", "data: {oops\n\n"));
        assertEquals("the MCP server sent a response that is not valid JSON",
                errorOf(run(shim(url(sse), Duration.ofSeconds(5)), LIST).get(0)).get("message"));
    }

    @Test
    void anEmptyJsonBodyProducesNoOutput() throws Exception {
        HttpServer server = stub(new CopyOnWriteArrayList<>(), answer(200, "application/json", ""));
        assertEquals(List.of(), run(shim(url(server), Duration.ofSeconds(5)), INITIALIZED));
    }

    // ---- request construction ----

    @Test
    void requestsCarryTheTokenAndTheSessionIdFromThenOn() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = stub(seen, exchange -> {
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-42");
            answer(200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}").send(exchange);
        });

        run(shim(url(server), Duration.ofSeconds(5)), INIT, LIST);

        assertEquals(2, seen.size());
        assertEquals("POST", seen.get(0).method());
        assertEquals("Bearer " + TOKEN, seen.get(0).authorization());
        assertNull(seen.get(0).sessionId(), "no session before the server assigns one");
        assertEquals(INIT, seen.get(0).body());
        assertEquals("session-42", seen.get(1).sessionId());
        assertEquals("Bearer " + TOKEN, seen.get(1).authorization());
    }

    // ---- input handling ----

    @Test
    void blankLinesAreSkippedAndBadInputGetsJsonRpcErrors() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = stub(seen, answer(202, null, ""));

        List<String> out = run(shim(url(server), Duration.ofSeconds(5)), "", "   ", "{not json", "[1,2]", "5");

        assertEquals(3, out.size());
        assertNull(json(out.get(0)).get("id"));
        assertEquals(StdioShim.PARSE_ERROR, errorOf(out.get(0)).get("code"));
        assertEquals("parse error: the message is not valid JSON", errorOf(out.get(0)).get("message"));
        assertEquals(StdioShim.INVALID_REQUEST, errorOf(out.get(1)).get("code"));
        assertEquals("only single JSON-RPC objects are supported, not batches", errorOf(out.get(1)).get("message"));
        assertEquals(StdioShim.INVALID_REQUEST, errorOf(out.get(2)).get("code"));
        assertEquals(0, seen.size(), "nothing invalid reaches the server");
    }

    // ---- configuration ----

    private static IllegalStateException config(Map<String, String> env) {
        return assertThrows(IllegalStateException.class, () -> StdioShim.fromEnvironment(env, null));
    }

    @Test
    void theTokenIsRequired() {
        assertEquals("MINECRAFT_MCP_TOKEN is not set", config(Map.of()).getMessage());
        assertEquals("MINECRAFT_MCP_TOKEN is not set", config(Map.of("MINECRAFT_MCP_TOKEN", "  ")).getMessage());
    }

    @Test
    void onlyHttpAndHttpsUrlsAreAccepted() {
        for (String url : new String[]{"ftp://127.0.0.1/mcp", "file:///x", "/mcp"}) {
            assertEquals("MINECRAFT_MCP_URL must be an http or https URL",
                    config(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_URL", url)).getMessage(), url);
        }
    }

    @Test
    void malformedUrlsAreAClearConfigurationError() {
        IllegalStateException e = config(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_URL", "127.0.0.1:25599/mcp"));
        assertEquals("MINECRAFT_MCP_URL is not a valid URL", e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void theTokenIsNeverSentInClearTextOffThisMachine() {
        for (String url : new String[]{"http://example.com/mcp", "http://192.168.1.5:25599/mcp", "http:/mcp"}) {
            String message = config(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_URL", url)).getMessage();
            assertTrue(message.contains("must use https"), url + ": " + message);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:25599/mcp", "http://LOCALHOST/mcp", "http://127.0.0.1:1/mcp",
            "http://[::1]:25599/mcp", "https://example.com/mcp", "https://192.168.1.5:8443/mcp"})
    void loopbackHttpAndAnyHttpsAreAccepted(String url) {
        assertNotNull(StdioShim.fromEnvironment(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_URL", url), log));
    }

    @Test
    void timeoutMustBeAPositiveInteger() {
        assertEquals("MINECRAFT_MCP_TIMEOUT_MS must be an integer number of milliseconds",
                config(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_TIMEOUT_MS", "soon")).getMessage());
        assertEquals("MINECRAFT_MCP_TIMEOUT_MS must be positive",
                config(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_TIMEOUT_MS", "0")).getMessage());
        assertEquals("MINECRAFT_MCP_TIMEOUT_MS must be positive",
                config(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_TIMEOUT_MS", "-5")).getMessage());
        assertNotNull(StdioShim.fromEnvironment(
                Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_TIMEOUT_MS", " 1 "), log));
    }

    @Test
    void theEnvironmentUrlAndDefaultsAreUsed() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = stub(seen, answer(202, null, ""));
        StdioShim shim = StdioShim.fromEnvironment(
                Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_URL", url(server)), log);

        run(shim, INITIALIZED);

        assertEquals(1, seen.size());
        assertEquals("Bearer " + TOKEN, seen.get(0).authorization());
        assertEquals("http://127.0.0.1:25599/mcp", StdioShim.DEFAULT_URL);
        assertEquals(30_000, StdioShim.DEFAULT_TIMEOUT_MS);
    }

    @Test
    void runReadsStdinAndWritesStdout() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = stub(seen, answer(200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StdioShim.run(Map.of("MINECRAFT_MCP_TOKEN", TOKEN, "MINECRAFT_MCP_URL", url(server)),
                new ByteArrayInputStream((LIST + "\n").getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), log);

        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runFailsFastWithoutAToken() {
        assertEquals("MINECRAFT_MCP_TOKEN is not set", assertThrows(IllegalStateException.class,
                () -> StdioShim.run(Map.of(), new ByteArrayInputStream(new byte[0]), new PrintStream(err), log))
                .getMessage());
    }

    @Test
    void mainBridgesStdinToStdout() throws Exception {
        // The Gradle test task puts a throwaway token in the environment (see build.gradle.kts).
        assumeTrue(System.getenv("MINECRAFT_MCP_TOKEN") != null);
        java.io.InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setIn(new ByteArrayInputStream("{not json\n".getBytes(StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            StdioShim.main(new String[0]);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertEquals(StdioShim.PARSE_ERROR,
                errorOf(captured.toString(StandardCharsets.UTF_8).strip()).get("code"));
    }
}
