package dev.aomarai.minecraftmcp.core;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Gatekeeper in front of every request the server accepts.
 *
 * <ol>
 *   <li><b>Host check (403):</b> the {@code Host} header must be exactly a
 *       loopback name, the configured bind host or one of the configured
 *       {@code allowedHosts}, with the real listening port. This defeats DNS
 *       rebinding, where a hostile web page resolves its own name to 127.0.0.1
 *       to reach a local server.</li>
 *   <li><b>Origin check (403):</b> if an {@code Origin} header is present it
 *       must be the scheme ({@code http://}, or {@code https://} when the endpoint
 *       serves TLS) plus one of those same authorities. Browsers
 *       always send {@code Origin} on cross-site requests; MCP clients that are
 *       not browsers send none.</li>
 *   <li><b>Bearer token (401):</b> {@code Authorization: Bearer <token>} is the
 *       only accepted form. The comparison uses {@link MessageDigest#isEqual},
 *       which takes the same time wherever the first mismatch is, so the token
 *       cannot be guessed byte by byte through timing. A failure returns a bare
 *       {@code 401} with {@code WWW-Authenticate: Bearer} and no detail about
 *       what was wrong.</li>
 * </ol>
 *
 * <p>A rejected request never reaches the MCP transport. Rejections are logged
 * with the remote address only: never the header value, never the token, and
 * throttled per address and in total (see {@link RejectionLog}). They are deliberately not
 * written to the audit file, so an anonymous caller cannot fill it.</p>
 */
final class BearerAuthFilter implements Filter {

    static final String CHALLENGE = "Bearer realm=\"minecraft-mcp\"";

    private static final String SCHEME = "Bearer ";
    private static final Set<String> WILDCARD_HOSTS = Set.of("0.0.0.0", "::", "[::]");

    private final byte[] expected;
    private final String bindHost;
    private final IntSupplier port;
    private final Exposure exposure;
    private final RejectionLog rejections;

    BearerAuthFilter(String token, String bindHost, IntSupplier port) {
        this(token, bindHost, port, Exposure.LOCAL);
    }

    BearerAuthFilter(String token, String bindHost, IntSupplier port, Exposure exposure) {
        this(token, bindHost, port, exposure, Clock.systemUTC());
    }

    BearerAuthFilter(String token, String bindHost, IntSupplier port, Exposure exposure, Clock clock) {
        Objects.requireNonNull(token, "token");
        if (token.length() < ConfigLoader.MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "token must be at least " + ConfigLoader.MIN_TOKEN_LENGTH + " characters");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
        this.port = port;
        this.exposure = Objects.requireNonNull(exposure, "exposure");
        this.rejections = new RejectionLog(clock);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        Set<String> authorities = authorities();
        if (!authorities.contains(lower(req.getHeader("Host")))) {
            reject(req, resp, HttpServletResponse.SC_FORBIDDEN, "unexpected Host header");
            return;
        }
        String origin = req.getHeader("Origin");
        if (origin != null && authorities.stream().noneMatch(a -> (exposure.scheme() + "://" + a).equals(lower(origin)))) {
            reject(req, resp, HttpServletResponse.SC_FORBIDDEN, "unexpected Origin header");
            return;
        }
        if (!tokenMatches(req.getHeader("Authorization"))) {
            resp.setHeader("WWW-Authenticate", CHALLENGE);
            reject(req, resp, HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean tokenMatches(String header) {
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return false;
        }
        byte[] presented = header.substring(SCHEME.length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }

    private Set<String> authorities() {
        Set<String> names = new LinkedHashSet<>(Set.of("localhost", "127.0.0.1", "[::1]"));
        if (!WILDCARD_HOSTS.contains(bindHost)) {
            names.add(bindHost.contains(":") && !bindHost.startsWith("[") ? "[" + bindHost + "]" : bindHost);
        }
        names.addAll(exposure.allowedHosts());
        Set<String> authorities = new LinkedHashSet<>();
        for (String name : names) {
            authorities.add(name.toLowerCase(java.util.Locale.ROOT) + ":" + port.getAsInt());
        }
        return authorities;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private void reject(HttpServletRequest req, HttpServletResponse resp, int status, String why) {
        rejections.rejected(req.getRemoteAddr(), why);
        resp.setStatus(status);
    }
}
