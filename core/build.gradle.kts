dependencies {
    api("io.modelcontextprotocol.sdk:mcp:2.0.1")

    // The MCP SDK's Streamable HTTP server transport is servlet-based and
    // compiled against Jakarta Servlet 6.1. Jetty 12.1 EE11 provides that
    // servlet API and an embeddable container. Kept as `implementation` so
    // Jetty does not leak into adapters' compile classpaths.
    implementation("org.eclipse.jetty.ee11:jetty-ee11-servlet:12.1.14")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
