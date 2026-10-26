package dev.aomarai.minecraftmcp.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** What it takes to reach the endpoint from another machine: HTTPS served by the endpoint, and the names clients may use. */
class RemoteAccessTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final int PORT = 4321;
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";

    @TempDir
    Path dir;

    // ---- the real thing: HTTPS from the endpoint's own certificate ----

    private McpCore core(Exposure exposure) {
        return new McpCore("127.0.0.1", 0, ToolCatalog.defaults().availableFor(GameServices.none()),
                new ToolDispatcher(McpConfig.defaults(), GameServices.none(), AuditLog.discarding()), TOKEN,
                Optional.empty(), exposure);
    }

    private static HttpRequest.Builder post(String url, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    @Test
    void anEndpointWithAKeystoreServesHttpsAndNotPlainHttp() throws Exception {
        TestKeystore keystore = TestKeystore.create(dir);
        try (McpCore core = core(new Exposure(Optional.of(keystore.tls()), Set.of()))) {
            core.start();
            HttpClient client = HttpClient.newBuilder().sslContext(keystore.clientContext()).build();
            String https = "https://localhost:" + core.port() + "/mcp";

            HttpResponse<String> ok = client.send(post(https, TOKEN).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("minecraft-mcp"), ok.body());
            assertEquals(401, client.send(post(https, null).build(), HttpResponse.BodyHandlers.ofString()).statusCode(),
                    "TLS does not replace the token");
            assertEquals(401, client.send(post(https, "wrong-token-wrong-token-wrong").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            assertThrows(IOException.class, () -> HttpClient.newHttpClient().send(
                    post("http://localhost:" + core.port() + "/mcp", TOKEN).build(), HttpResponse.BodyHandlers.ofString()),
                    "a plain HTTP request to the TLS port gets no answer");
        }
    }

    @Test
    void aClientThatDoesNotTrustTheCertificateIsRefused() throws Exception {
        TestKeystore keystore = TestKeystore.create(dir);
        try (McpCore core = core(new Exposure(Optional.of(keystore.tls()), Set.of()))) {
            core.start();

            assertThrows(IOException.class, () -> HttpClient.newHttpClient().send(
                    post("https://localhost:" + core.port() + "/mcp", TOKEN).build(), HttpResponse.BodyHandlers.ofString()));
        }
    }

    @Test
    void aBrowserOriginOnTheTlsEndpointMustBeHttps() throws Exception {
        TestKeystore keystore = TestKeystore.create(dir);
        try (McpCore core = core(new Exposure(Optional.of(keystore.tls()), Set.of()))) {
            core.start();
            HttpClient client = HttpClient.newBuilder().sslContext(keystore.clientContext()).build();
            String url = "https://localhost:" + core.port() + "/mcp";
            String origin = "localhost:" + core.port();

            assertEquals(200, client.send(post(url, TOKEN).header("Origin", "https://" + origin).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, client.send(post(url, TOKEN).header("Origin", "http://" + origin).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    // ---- the Host check, with and without extra names ----

    private record Outcome(int status, boolean passedThrough) {
    }

    private static Outcome run(BearerAuthFilter filter, Map<String, String> headers) throws Exception {
        int[] status = {200};
        boolean[] passed = {false};
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                RemoteAccessTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> headers.get((String) args[0]);
                    case "getRemoteAddr" -> "203.0.113.9";
                    default -> null;
                });
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                RemoteAccessTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setStatus")) {
                        status[0] = (Integer) args[0];
                    }
                    return null;
                });
        filter.doFilter(request, response, (req, resp) -> passed[0] = true);
        return new Outcome(status[0], passed[0]);
    }

    private static Map<String, String> request(String host) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", host);
        headers.put("Authorization", "Bearer " + TOKEN);
        return headers;
    }

    private static BearerAuthFilter filter(String bindHost, Exposure exposure) {
        return new BearerAuthFilter(TOKEN, bindHost, () -> PORT, exposure);
    }

    @Test
    void aNameThatIsNotListedIsRefusedByDefaultAndAcceptedOnceItIs() throws Exception {
        assertEquals(403, run(filter("0.0.0.0", Exposure.LOCAL), request("mc.example.com:" + PORT)).status());
        assertEquals(403, run(filter("0.0.0.0", Exposure.LOCAL), request("192.168.1.5:" + PORT)).status());

        Exposure listed = new Exposure(Optional.empty(), Set.of("mc.example.com", "192.168.1.5"));
        assertTrue(run(filter("0.0.0.0", listed), request("mc.example.com:" + PORT)).passedThrough());
        assertTrue(run(filter("0.0.0.0", listed), request("MC.Example.com:" + PORT)).passedThrough(), "names are case-insensitive");
        assertTrue(run(filter("0.0.0.0", listed), request("192.168.1.5:" + PORT)).passedThrough());
        assertEquals(403, run(filter("0.0.0.0", listed), request("mc.example.com:" + (PORT + 1))).status(), "only the real port");
        assertEquals(403, run(filter("0.0.0.0", listed), request("evil.example.com:" + PORT)).status());
        assertTrue(run(filter("0.0.0.0", listed), request("localhost:" + PORT)).passedThrough(), "loopback stays accepted");
    }

    @Test
    void aListedNameStillNeedsTheToken() throws Exception {
        Exposure listed = new Exposure(Optional.empty(), Set.of("mc.example.com"));
        Map<String, String> headers = request("mc.example.com:" + PORT);
        headers.remove("Authorization");

        Outcome outcome = run(filter("0.0.0.0", listed), headers);

        assertEquals(401, outcome.status());
        assertFalse(outcome.passedThrough());
    }

    @Test
    void theOriginSchemeFollowsTls() throws Exception {
        Exposure plain = Exposure.LOCAL;
        Exposure secure = new Exposure(Optional.of(new Tls(dir.resolve("k.p12"), "pw")), Set.of());
        Map<String, String> http = request("localhost:" + PORT);
        http.put("Origin", "http://localhost:" + PORT);
        Map<String, String> https = request("localhost:" + PORT);
        https.put("Origin", "https://localhost:" + PORT);

        assertTrue(run(filter("127.0.0.1", plain), http).passedThrough());
        assertEquals(403, run(filter("127.0.0.1", plain), https).status());
        assertTrue(run(filter("127.0.0.1", secure), https).passedThrough());
        assertEquals(403, run(filter("127.0.0.1", secure), http).status());
    }

    @Test
    void anExposureHasNoExtraNamesAndNoTlsWhenLocalAndKeepsACopyOfWhatItIsGiven() {
        assertEquals(Optional.empty(), Exposure.LOCAL.tls());
        assertEquals(Set.of(), Exposure.LOCAL.allowedHosts());
        assertEquals("http", Exposure.LOCAL.scheme());

        java.util.Set<String> names = new java.util.HashSet<>(Set.of("a.example"));
        Exposure exposure = new Exposure(Optional.empty(), names);
        names.add("b.example");
        assertEquals(Set.of("a.example"), exposure.allowedHosts());
    }

    // ---- the configuration ----

    private static McpConfig remote(Set<String> hosts, Optional<Tls> tls) {
        return new McpConfig("0.0.0.0", 25599, false, false, 32768, java.time.Duration.ofSeconds(8), Set.of(), Set.of(),
                true, hosts, tls);
    }

    @Test
    void allowedHostsAreNormalizedAndMustBeBareNames() {
        assertEquals(Set.of("mc.example.com", "192.168.1.5", "[::1]", "my-host"),
                remote(Set.of(" MC.Example.com ", "192.168.1.5", "[::1]", "my-host"), Optional.empty()).allowedHosts());
        assertEquals(Set.of(), McpConfig.defaults().allowedHosts());
        assertEquals(Optional.empty(), McpConfig.defaults().tls());

        for (String bad : List.of("http://mc.example.com", "mc.example.com:25599", "mc.example.com/mcp", "", "a b",
                "-bad", "bad-", ".bad", "ex*ample", "[::1")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> remote(Set.of(bad), Optional.empty()), "'" + bad + "'");
            assertTrue(e.getMessage().startsWith("allowedHosts entry '" + bad + "' must be a host name or address"), e.getMessage());
        }
    }

    @Test
    void theTlsSettingsNeverShowTheirPassword() {
        Tls tls = new Tls(Path.of("keystore.p12"), "s3cret-password");

        assertEquals("Tls[keystore=keystore.p12, password=<redacted>]", tls.toString());
        assertFalse(remote(Set.of(), Optional.of(tls)).toString().contains("s3cret-password"));
        assertEquals("tlsKeystorePassword must not be empty",
                assertThrows(IllegalArgumentException.class, () -> new Tls(Path.of("k"), "")).getMessage());
        assertThrows(NullPointerException.class, () -> new Tls(null, "pw"));
        assertThrows(NullPointerException.class, () -> new Tls(Path.of("k"), null));
    }

    @Test
    void theConfigurationHandsItsExposureToTheListener() {
        Tls tls = new Tls(Path.of("keystore.p12"), "pw");

        Exposure exposure = remote(Set.of("mc.example.com"), Optional.of(tls)).exposure();

        assertEquals(Optional.of(tls), exposure.tls());
        assertEquals(Set.of("mc.example.com"), exposure.allowedHosts());
        assertEquals("https", exposure.scheme());
    }

    @Test
    void theLoaderReadsTheKeysFromTheFileAndTheEnvironmentAndWritesThemToANewFile() throws IOException {
        Path file = dir.resolve("minecraft-mcp.json");

        ConfigLoader.Loaded fresh = ConfigLoader.load(file, Map.of());
        String written = Files.readString(file);
        assertEquals(Set.of(), fresh.config().allowedHosts());
        assertEquals(Optional.empty(), fresh.config().tls());
        assertTrue(written.contains("\"allowedHosts\": []"), written);
        assertTrue(written.contains("\"tlsKeystore\": \"\""), written);
        assertTrue(written.contains("\"tlsKeystorePassword\": \"\""), written);

        McpConfig fromEnv = ConfigLoader.load(file, Map.of(
                "MINECRAFT_MCP_HOST", "0.0.0.0", "MINECRAFT_MCP_ALLOW_REMOTE", "true",
                "MINECRAFT_MCP_ALLOWED_HOSTS", "mc.example.com, 192.168.1.5")).config();
        assertEquals(Set.of("mc.example.com", "192.168.1.5"), fromEnv.allowedHosts());
    }

    @Test
    void theKeystoreAndItsPasswordCanComeFromTheEnvironment() throws IOException {
        Path file = dir.resolve("minecraft-mcp.json");
        Path keystore = dir.resolve("from-env.p12");
        Files.writeString(keystore, "only its presence is checked here");

        McpConfig config = ConfigLoader.load(file, Map.of(
                "MINECRAFT_MCP_TLS_KEYSTORE", keystore.toString(),
                "MINECRAFT_MCP_TLS_KEYSTORE_PASSWORD", "env-password")).config();

        assertEquals(Optional.of(new Tls(keystore.toAbsolutePath().normalize(), "env-password")), config.tls());
        assertFalse(Files.readString(file).contains("env-password"), "an environment value is never written back to the file");
    }

    @Test
    void theAuditLogRedactsTheTokenAndTheKeystorePasswordButOnlyWhenThereIsOne() {
        McpConfig plain = McpConfig.defaults();
        McpConfig secure = remote(Set.of(), Optional.of(new Tls(Path.of("keystore.p12"), "pw-secret")));

        assertEquals(List.of(TOKEN), McpService.secrets(TOKEN, plain));
        assertEquals(List.of(TOKEN, "pw-secret"), McpService.secrets(TOKEN, secure));
    }

    @Test
    void aKeystoreIsFoundNextToTheConfigFileOrByAbsolutePath() throws Exception {
        Path config = dir.resolve("conf").resolve("minecraft-mcp.json");
        Files.createDirectories(config.getParent());
        Path relative = config.getParent().resolve("keystore.p12");
        Files.writeString(relative, "not a real keystore; only its presence is checked here");
        Files.writeString(config, "{\"tlsKeystore\": \"keystore.p12\", \"tlsKeystorePassword\": \"pw\", \"token\": \"" + TOKEN + "\"}");

        assertEquals(Optional.of(new Tls(relative.toAbsolutePath().normalize(), "pw")), ConfigLoader.load(config, Map.of()).config().tls());

        Files.writeString(config, "{\"tlsKeystore\": \"" + relative.toAbsolutePath().toString().replace("\\", "\\\\")
                + "\", \"tlsKeystorePassword\": \"pw\", \"token\": \"" + TOKEN + "\"}");
        assertEquals(Optional.of(new Tls(relative.toAbsolutePath().normalize(), "pw")), ConfigLoader.load(config, Map.of()).config().tls());
    }

    @Test
    void tlsSettingsThatCouldQuietlyLeaveTheEndpointUnencryptedAreErrors() throws IOException {
        Path file = dir.resolve("minecraft-mcp.json");
        Files.writeString(file, "{\"tlsKeystorePassword\": \"pw\", \"token\": \"" + TOKEN + "\"}");
        assertEquals("tlsKeystorePassword is set but tlsKeystore is not, so the endpoint would still speak plain HTTP",
                assertThrows(ConfigException.class, () -> ConfigLoader.load(file, Map.of())).getMessage());

        Files.writeString(file, "{\"tlsKeystore\": \"missing.p12\", \"tlsKeystorePassword\": \"pw\", \"token\": \"" + TOKEN + "\"}");
        String missing = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, Map.of())).getMessage();
        assertTrue(missing.startsWith("tlsKeystore does not exist: "), missing);
        assertTrue(missing.endsWith("missing.p12"), missing);

        Files.writeString(dir.resolve("present.p12"), "x");
        Files.writeString(file, "{\"tlsKeystore\": \"present.p12\", \"token\": \"" + TOKEN + "\"}");
        assertEquals("tlsKeystorePassword must be set when tlsKeystore is",
                assertThrows(ConfigException.class, () -> ConfigLoader.load(file, Map.of())).getMessage());

        Files.writeString(file, "{\"tlsKeystore\": 5, \"token\": \"" + TOKEN + "\"}");
        assertEquals("tlsKeystore must be a string", assertThrows(ConfigException.class, () -> ConfigLoader.load(file, Map.of())).getMessage());
    }

    // ---- the whole service ----

    @Test
    void theServiceServesHttpsFromTheConfigAndKeepsThePasswordOutOfTheAuditLog() throws Exception {
        TestKeystore keystore = TestKeystore.create(dir);
        Path config = dir.resolve("minecraft-mcp.json");
        Files.writeString(config, "{\"tlsKeystore\": \"keystore.p12\", \"tlsKeystorePassword\": \"" + TestKeystore.PASSWORD
                + "\", \"token\": \"" + TOKEN + "\"}");
        Path audit = dir.resolve("audit.jsonl");

        try (LogCapture log = new LogCapture(); McpService service = McpService.start(config, audit, c -> GameServices.none(),
                Map.of("MINECRAFT_MCP_PORT", String.valueOf(freePort())))) {
            HttpClient client = HttpClient.newBuilder().sslContext(keystore.clientContext()).build();
            String url = "https://localhost:" + service.port() + "/mcp";

            assertEquals(200, client.send(post(url, TOKEN).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(log.lines().stream().anyMatch(line -> line.startsWith("Serving HTTPS on {0} with the certificate in {1}")),
                    log.lines().toString());
            assertTrue(log.lines().stream().noneMatch(line -> line.contains(TestKeystore.PASSWORD)), "the password is never logged");
        }
    }

    @Test
    void listeningOnTheNetworkWithoutTlsStillWarnsAndSaysHowToFixIt() throws Exception {
        Path config = dir.resolve("minecraft-mcp.json");
        Files.writeString(config, "{\"host\": \"0.0.0.0\", \"allowRemote\": true, \"token\": \"" + TOKEN + "\"}");

        try (LogCapture log = new LogCapture(); McpService service = McpService.start(config, dir.resolve("a.jsonl"),
                c -> GameServices.none(), Map.of("MINECRAFT_MCP_PORT", String.valueOf(freePort())))) {
            assertTrue(log.lines().stream().anyMatch(line -> line.contains("over plain HTTP")
                    && line.contains("Serve HTTPS with tlsKeystore")), log.lines().toString());
        }
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
