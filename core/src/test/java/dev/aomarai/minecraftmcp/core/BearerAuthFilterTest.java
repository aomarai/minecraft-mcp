package dev.aomarai.minecraftmcp.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BearerAuthFilterTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final int PORT = 4321;

    /** Result of pushing one request through the filter. */
    private record Outcome(int status, Map<String, String> responseHeaders, boolean passedThrough) {
    }

    private static Outcome run(BearerAuthFilter filter, Map<String, String> headers) throws Exception {
        Map<String, String> responseHeaders = new HashMap<>();
        int[] status = {200};
        boolean[] passed = {false};
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                BearerAuthFilterTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> headers.get((String) args[0]);
                    case "getRemoteAddr" -> "203.0.113.9";
                    default -> null;
                });
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                BearerAuthFilterTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> status[0] = (Integer) args[0];
                        case "setHeader" -> responseHeaders.put((String) args[0], (String) args[1]);
                        default -> {
                        }
                    }
                    return null;
                });
        filter.doFilter(request, response, (req, resp) -> passed[0] = true);
        return new Outcome(status[0], responseHeaders, passed[0]);
    }

    private static BearerAuthFilter filter() {
        return new BearerAuthFilter(TOKEN, "127.0.0.1", () -> PORT);
    }

    private static Map<String, String> good() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "127.0.0.1:" + PORT);
        headers.put("Authorization", "Bearer " + TOKEN);
        return headers;
    }

    @Test
    void aValidRequestPassesThrough() throws Exception {
        Outcome outcome = run(filter(), good());
        assertTrue(outcome.passedThrough());
        assertEquals(200, outcome.status());
    }

    @Test
    void missingAuthorizationIsA401WithAChallengeAndNothingElse() throws Exception {
        Map<String, String> headers = good();
        headers.remove("Authorization");

        Outcome outcome = run(filter(), headers);

        assertFalse(outcome.passedThrough());
        assertEquals(401, outcome.status());
        assertEquals(Map.of("WWW-Authenticate", "Bearer realm=\"minecraft-mcp\""), outcome.responseHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bearer wrong-token-wrong-token-wrong-tok",
            "Bearer " + TOKEN + "x",
            "Bearer " + "0123456789abcdef0123456789abcde",
            "Bearer ",
            "Bearer",
            "Basic " + TOKEN,
            "Token " + TOKEN,
            TOKEN,
            "",
    })
    void wrongTokensAndSchemesAreA401(String header) throws Exception {
        Map<String, String> headers = good();
        headers.put("Authorization", header);

        Outcome outcome = run(filter(), headers);

        assertFalse(outcome.passedThrough(), header);
        assertEquals(401, outcome.status(), header);
        assertEquals("Bearer realm=\"minecraft-mcp\"", outcome.responseHeaders().get("WWW-Authenticate"));
    }

    @Test
    void theSchemeIsCaseInsensitiveAndSurroundingSpacesAreTolerated() throws Exception {
        Map<String, String> headers = good();
        headers.put("Authorization", "bearer   " + TOKEN + "  ");
        assertTrue(run(filter(), headers).passedThrough());
        headers.put("Authorization", "BEARER " + TOKEN);
        assertTrue(run(filter(), headers).passedThrough());
    }

    @Test
    void aTokenIsRequiredEvenForLoopbackRequests() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "localhost:" + PORT);
        assertEquals(401, run(filter(), headers).status());
    }

    // ---- Host: DNS rebinding defence ----

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1:4321", "localhost:4321", "LOCALHOST:4321", "[::1]:4321"})
    void loopbackHostsWithTheRealPortAreAccepted(String host) throws Exception {
        Map<String, String> headers = good();
        headers.put("Host", host);
        assertTrue(run(filter(), headers).passedThrough(), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example:4321", "evil.example", "127.0.0.1", "localhost:80", "127.0.0.1:4322",
            "0.0.0.0:4321", "[::]:4321", "127.0.0.1:4321.evil.example", ""})
    void otherHostsAreForbiddenBeforeTheTokenIsEvenChecked(String host) throws Exception {
        Map<String, String> headers = good();
        headers.put("Host", host);

        Outcome outcome = run(filter(), headers);

        assertFalse(outcome.passedThrough(), host);
        assertEquals(403, outcome.status(), host);
        assertTrue(outcome.responseHeaders().isEmpty(), "a 403 gives no challenge");
    }

    @Test
    void aMissingHostIsForbidden() throws Exception {
        Map<String, String> headers = good();
        headers.remove("Host");
        assertEquals(403, run(filter(), headers).status());
    }

    @Test
    void theConfiguredBindHostIsAcceptedAndWildcardsAreNot() throws Exception {
        Map<String, String> headers = good();
        headers.put("Host", "mc.internal:" + PORT);
        assertEquals(403, run(filter(), headers).status());

        BearerAuthFilter named = new BearerAuthFilter(TOKEN, "MC.internal", () -> PORT);
        assertTrue(run(named, headers).passedThrough());

        BearerAuthFilter wildcard = new BearerAuthFilter(TOKEN, "0.0.0.0", () -> PORT);
        headers.put("Host", "0.0.0.0:" + PORT);
        assertEquals(403, run(wildcard, headers).status());
        assertTrue(run(wildcard, good()).passedThrough(), "loopback still works on a wildcard bind");
    }

    @Test
    void ipv6BindHostsAreBracketed() throws Exception {
        Map<String, String> headers = good();
        headers.put("Host", "[fd00::1]:" + PORT);
        assertTrue(run(new BearerAuthFilter(TOKEN, "fd00::1", () -> PORT), headers).passedThrough());
        assertTrue(run(new BearerAuthFilter(TOKEN, "[fd00::1]", () -> PORT), headers).passedThrough());
        assertEquals(403, run(new BearerAuthFilter(TOKEN, "[::]", () -> PORT), headers).status());
        assertEquals(403, run(new BearerAuthFilter(TOKEN, "::", () -> PORT), headers).status());
    }

    @Test
    void thePortIsReadAtRequestTime() throws Exception {
        int[] port = {1000};
        BearerAuthFilter dynamic = new BearerAuthFilter(TOKEN, "127.0.0.1", () -> port[0]);
        Map<String, String> headers = good();
        headers.put("Host", "127.0.0.1:2000");
        assertEquals(403, run(dynamic, headers).status());
        port[0] = 2000;
        assertTrue(run(dynamic, headers).passedThrough());
    }

    // ---- Origin: browser cross-site defence ----

    @Test
    void aMatchingOriginIsAccepted() throws Exception {
        Map<String, String> headers = good();
        headers.put("Origin", "http://127.0.0.1:" + PORT);
        assertTrue(run(filter(), headers).passedThrough());
        headers.put("Origin", "HTTP://LOCALHOST:" + PORT);
        assertTrue(run(filter(), headers).passedThrough());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example", "https://127.0.0.1:4321", "http://127.0.0.1", "http://127.0.0.1:4321/",
            "null", "", "http://evil.example:4321"})
    void foreignOriginsAreForbiddenEvenWithAValidToken(String origin) throws Exception {
        Map<String, String> headers = good();
        headers.put("Origin", origin);

        Outcome outcome = run(filter(), headers);

        assertFalse(outcome.passedThrough(), origin);
        assertEquals(403, outcome.status(), origin);
    }

    // ---- construction ----

    @Test
    void rejectsWeakOrMissingTokensAndNullHost() {
        assertThrows(NullPointerException.class, () -> new BearerAuthFilter(null, "127.0.0.1", () -> PORT));
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthFilter("short", "127.0.0.1", () -> PORT));
        assertThrows(IllegalArgumentException.class,
                () -> new BearerAuthFilter("x".repeat(15), "127.0.0.1", () -> PORT));
        assertDoesNotThrow(() -> new BearerAuthFilter("x".repeat(16), "127.0.0.1", () -> PORT));
        assertThrows(NullPointerException.class, () -> new BearerAuthFilter(TOKEN, null, () -> PORT));
    }

    // ---- logging ----

    @Test
    void rejectionsAreLoggedWithTheRemoteAddressButNeverTheCredentials() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            Map<String, String> headers = good();
            headers.put("Authorization", "Bearer leaked-secret-value-1234567890");
            run(filter(), headers);
            Map<String, String> badHost = good();
            badHost.put("Host", "evil.example");
            run(filter(), badHost);
            run(filter(), good());

            List<String> logged = logs.lines();
            assertEquals(2, logged.size(), logged.toString());
            for (String line : logged) {
                assertTrue(line.contains("203.0.113.9"), line);
                assertFalse(line.contains("leaked-secret"), line);
                assertFalse(line.contains(TOKEN), line);
            }
            assertTrue(logged.get(0).contains("missing or invalid bearer token"));
            assertTrue(logged.get(1).contains("unexpected Host header"));
        }
    }

    @Test
    void aFilterThatKeepsGettingTheSameBadRequestLogsItOnce() throws Exception {
        BearerAuthFilter one = filter();
        Map<String, String> headers = good();
        headers.put("Authorization", "Bearer nope");
        try (LogCapture logs = new LogCapture()) {
            for (int i = 0; i < 5; i++) {
                assertEquals(401, run(one, headers).status());
            }

            assertEquals(1, logs.lines().size(), logs.lines().toString());
        }
    }

    @Test
    void anUnexpectedOriginIsLoggedAsSuch() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            Map<String, String> headers = good();
            headers.put("Origin", "http://evil.example");
            run(filter(), headers);
            assertTrue(logs.lines().get(0).contains("unexpected Origin header"), logs.lines().toString());
        }
    }
}
