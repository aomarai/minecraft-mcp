package dev.aomarai.minecraftmcp.core;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Minimal Streamable HTTP bootstrap for the MCP core.
 *
 * <p>Owns a Jetty container and the SDK's servlet transport. It deliberately
 * imports no Minecraft, Fabric, NeoForge or Bukkit classes: adapters inject a
 * {@link GameAccess} implementation later, but the protocol layer is testable
 * on a plain JVM.
 *
 * <p>The endpoint is {@code /mcp}. The server advertises the {@link ToolCatalog}
 * descriptors; call handlers and the policy gate are wired in issue #10.
 */
public final class McpCore implements AutoCloseable {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final String DEFAULT_ENDPOINT = "/mcp";

    private final String host;
    private final int requestedPort;
    private final HttpServletStreamableServerTransportProvider transport;
    private final McpSyncServer mcp;
    private final Server jetty;
    private final ServerConnector connector;

    public McpCore() {
        this(DEFAULT_HOST, 0);
    }

    public McpCore(String host, int port) {
        this(host, port, ToolCatalog.defaults());
    }

    public McpCore(String host, int port, ToolCatalog catalog) {
        this.host = host;
        this.requestedPort = port;

        this.transport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(DEFAULT_ENDPOINT)
                .build();

        this.mcp = McpServer.sync(transport)
                .serverInfo("minecraft-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(catalog.specs())
                .build();

        this.jetty = new Server();
        this.connector = new ServerConnector(jetty);
        connector.setHost(host);
        connector.setPort(port);
        jetty.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(transport), DEFAULT_ENDPOINT + "/*");
        jetty.setHandler(context);
    }

    /** Starts the HTTP listener. Call before connecting a client. */
    public void start() throws Exception {
        jetty.start();
    }

    /** The host the listener is bound to. */
    public String host() {
        return connector.getHost();
    }

    /** The actual bound port. Useful when constructed with port {@code 0}. */
    public int port() {
        return connector.getLocalPort();
    }

    /** The servlet transport, exposed to tests that exercise shutdown behavior. */
    HttpServletStreamableServerTransportProvider transport() {
        return transport;
    }

    /** The MCP server instance, for registering tools/resources in later issues. */
    public McpSyncServer server() {
        return mcp;
    }

    @Override
    public void close() throws Exception {
        try {
            mcp.closeGracefully();
        } finally {
            jetty.stop();
        }
    }
}
