package dev.aomarai.minecraftmcp.shim;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bridges an MCP host that only launches stdio servers to the mod's Streamable
 * HTTP endpoint.
 *
 * <p>Each line on stdin is one JSON-RPC message. It is POSTed to the endpoint
 * with the bearer token and the response, JSON or an SSE stream, is written
 * back as one compact JSON line per message on stdout. Messages are handled in
 * order, so {@code initialize} always completes before the notification that
 * follows it. The {@code Mcp-Session-Id} the server returns is remembered and
 * sent on later requests.</p>
 *
 * <p>After {@code initialize} succeeds the shim also opens the server's optional message stream
 * ({@code GET} with the session id, {@code Accept: text/event-stream}) on a daemon thread and writes every message
 * the server sends on its own, such as {@code notifications/resources/updated} for a subscribed resource, to
 * stdout as one line, exactly like a reply. Writes to stdout are serialized, so lines never interleave. The stream
 * is reopened with a growing delay when it ends or fails, and given up quietly when the server answers {@code 405}
 * (it offers none) or {@code 404} (the session ended).</p>
 *
 * <p>A {@code 404} on a request that carried a session id means the session has ended (the server restarted or
 * was reloaded). The shim forgets the id, so the next request does not repeat it, and says the host must
 * reconnect: the Streamable HTTP spec has the client start a new session with {@code initialize}.</p>
 *
 * <p>Failures never hang the host: an unreachable server, a timeout, an HTTP
 * error status or an unreadable response becomes a JSON-RPC error response for
 * that request's id, with a message that names the problem.</p>
 *
 * <p>Configuration comes from the environment, never from arguments, so the
 * token does not appear in process listings:
 * {@code MINECRAFT_MCP_TOKEN} (required), {@code MINECRAFT_MCP_URL} (default
 * {@value #DEFAULT_URL}) and {@code MINECRAFT_MCP_TIMEOUT_MS} (default
 * 30000). The token is never logged and is only sent over plain HTTP to a
 * loopback host; anything else must use HTTPS.</p>
 */
public final class StdioShim {

    public static final String DEFAULT_URL = "http://127.0.0.1:25599/mcp";
    static final String ENV_URL = "MINECRAFT_MCP_URL";
    static final String ENV_TOKEN = "MINECRAFT_MCP_TOKEN";
    static final String ENV_TIMEOUT_MS = "MINECRAFT_MCP_TIMEOUT_MS";
    static final long DEFAULT_TIMEOUT_MS = 30_000;

    static final Duration RECONNECT_FIRST = Duration.ofSeconds(1);
    static final Duration RECONNECT_CAP = Duration.ofSeconds(30);

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int SERVER_ERROR = -32000;
    static final String SESSION_ENDED = "the MCP session ended (the server restarted or was reloaded): "
            + "reconnect, so that the host sends initialize again";

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;
    private final PrintStream log;
    private final Supplier<Backoff> backoffs;
    private volatile String sessionId;
    private Thread stream;

    StdioShim(URI endpoint, String token, Duration timeout, PrintStream log) {
        this(endpoint, token, timeout, log, () -> new Backoff(RECONNECT_FIRST, RECONNECT_CAP));
    }

    StdioShim(URI endpoint, String token, Duration timeout, PrintStream log, Supplier<Backoff> backoffs) {
        this.backoffs = backoffs;
        this.endpoint = endpoint;
        this.token = token;
        this.timeout = timeout;
        this.log = log;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Entry point. A configuration problem is thrown, which makes the JVM exit non-zero. */
    public static void main(String[] args) throws IOException {
        run(System.getenv(), System.in, System.out, System.err);
    }

    static void run(Map<String, String> env, InputStream in, PrintStream out, PrintStream err) throws IOException {
        StdioShim shim = fromEnvironment(env, err);
        shim.pump(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), out);
    }

    static StdioShim fromEnvironment(Map<String, String> env, PrintStream log) {
        String token = env.get(ENV_TOKEN);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(ENV_TOKEN + " is not set");
        }
        URI endpoint = parseUrl(env.getOrDefault(ENV_URL, DEFAULT_URL));
        String scheme = endpoint.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalStateException(ENV_URL + " must be an http or https URL");
        }
        if ("http".equals(scheme) && !LOOPBACK.contains(String.valueOf(endpoint.getHost()).toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    ENV_URL + " must use https unless it points at this machine: the token is not sent in clear text");
        }
        long millis;
        try {
            millis = Long.parseLong(env.getOrDefault(ENV_TIMEOUT_MS, String.valueOf(DEFAULT_TIMEOUT_MS)).strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(ENV_TIMEOUT_MS + " must be an integer number of milliseconds", e);
        }
        if (millis < 1) {
            throw new IllegalStateException(ENV_TIMEOUT_MS + " must be positive");
        }
        return new StdioShim(endpoint, token, Duration.ofMillis(millis), log);
    }

    private static URI parseUrl(String raw) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(ENV_URL + " is not a valid URL", e);
        }
    }

    /** Handles every line until the input ends. */
    void pump(BufferedReader in, PrintStream out) throws IOException {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    handle(line, out);
                }
            }
        } finally {
            stopStream();
        }
    }

    private void handle(String line, PrintStream out) throws IOException {
        Object parsed;
        try {
            parsed = JSON.readValue(line, Object.class);
        } catch (IOException | RuntimeException e) {
            error(out, null, PARSE_ERROR, "parse error: the message is not valid JSON");
            return;
        }
        if (!(parsed instanceof Map<?, ?> message)) {
            error(out, null, INVALID_REQUEST, "only single JSON-RPC objects are supported, not batches");
            return;
        }
        Object id = message.get("id");
        try {
            forward(line, id, out);
            // The server assigns a session only to an initialize it accepted.
            if ("initialize".equals(message.get("method")) && sessionId != null) {
                startStream(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(out, id, SERVER_ERROR, "interrupted while waiting for the MCP server");
        } catch (IOException e) {
            error(out, id, SERVER_ERROR, "cannot reach the MCP server at " + endpoint + ": " + describe(e));
        }
    }

    private void forward(String body, Object id, PrintStream out) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        String carried = sessionId;
        if (carried != null) {
            request.header("Mcp-Session-Id", carried);
        }
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            int status = response.statusCode();
            if (status == 404 && carried != null) {
                sessionId = null;
                error(out, id, SERVER_ERROR, SESSION_ENDED);
            } else if (status >= 400) {
                error(out, id, SERVER_ERROR, describeStatus(status));
            } else {
                // A 202 or 204 has no body, so there is nothing to relay.
                relay(response.headers().firstValue("Content-Type").orElse(""), stream, id, out);
            }
        }
    }

    /** (Re)starts the message stream for the current session, ending the one of an earlier session. */
    private void startStream(PrintStream out) {
        stopStream();
        String session = sessionId;
        Backoff backoff = backoffs.get();
        stream = Thread.ofPlatform().daemon().name("minecraft-mcp-shim-stream").start(() -> follow(session, backoff, out));
    }

    private void stopStream() {
        if (stream != null) {
            stream.interrupt();
        }
    }

    /** Opens the stream, relays it, and opens it again after a pause, until told to stop or the server has none. */
    void follow(String session, Backoff backoff, PrintStream out) {
        try {
            while (openStream(session, backoff, out)) {
                Thread.sleep(backoff.next().toMillis());
            }
        } catch (InterruptedException e) {
            // stopStream() ended this stream: the host went away or initialized again. The flag stays set, so
            // whoever runs this thread can still tell that it was asked to stop.
            Thread.currentThread().interrupt();
        }
    }

    /** The thread following the server's message stream, or null; for tests to wait on. */
    Thread stream() {
        return stream;
    }

    /** One connection: false when there is no point in trying again (405: no stream offered; 404: session gone). */
    private boolean openStream(String session, Backoff backoff, PrintStream out) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .header("Mcp-Session-Id", session)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status < 400) {
                    backoff.reset();
                    relay("text/event-stream", body, null, out);
                }
            }
            if (status == 405 || status == 404) {
                return false;
            }
            if (status >= 400) {
                log.println("minecraft-mcp-shim: the message stream answered HTTP " + status + "; trying again");
            }
        } catch (IOException e) {
            log.println("minecraft-mcp-shim: the message stream ended: " + describe(e));
        }
        return true;
    }

    private void relay(String contentType, InputStream stream, Object id, PrintStream out) throws IOException {
        if (contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            List<String> data = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emit(out, String.join("\n", data), id);
                    data.clear();
                } else if (line.startsWith("data:")) {
                    data.add(line.substring(5).strip());
                }
            }
            emit(out, String.join("\n", data), id);
        } else {
            emit(out, new String(stream.readAllBytes(), StandardCharsets.UTF_8), id);
        }
    }

    /** Writes the JSON as one compact line, or an error for {@code id} if it is not JSON. Empty text is skipped. */
    private void emit(PrintStream out, String text, Object id) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        try {
            line(out, JSON.writeValueAsString(JSON.readValue(text, Object.class)));
        } catch (IOException | RuntimeException e) {
            error(out, id, SERVER_ERROR, "the MCP server sent a response that is not valid JSON");
        }
    }

    private void error(PrintStream out, Object id, int code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        log.println("minecraft-mcp-shim: " + message);
        line(out, JSON.writeValueAsString(response));
    }

    /** One whole line, never mixed with another thread's. */
    private static void line(PrintStream out, String json) {
        synchronized (out) {
            out.print(json + "\n");
            out.flush();
        }
    }

    private static String describeStatus(int status) {
        return switch (status) {
            case 401 -> "the MCP server rejected the token (HTTP 401): check " + ENV_TOKEN;
            case 403 -> "the MCP server refused the request (HTTP 403): check the URL host and port";
            case 404 -> "no MCP endpoint at that URL (HTTP 404): check " + ENV_URL;
            default -> "the MCP server answered HTTP " + status;
        };
    }

    private static String describe(IOException e) {
        return Objects.toString(e.getMessage(), e.getClass().getSimpleName());
    }
}
