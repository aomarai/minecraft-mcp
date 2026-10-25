plugins {
    id("info.solidsoft.pitest") version "1.19.0"
}

dependencies {
    api("io.modelcontextprotocol.sdk:mcp:2.1.0")

    // The MCP SDK's Streamable HTTP server transport is servlet-based and
    // compiled against Jakarta Servlet 6.1. Jetty 12.1 EE11 provides that
    // servlet API and an embeddable container. Kept as `implementation` so
    // Jetty does not leak into adapters' compile classpaths.
    implementation("org.eclipse.jetty.ee11:jetty-ee11-servlet:12.1.14")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.20")
}

// Mutation testing. Run with `./gradlew :core:pitest`; it is not part of
// `build` because it is slow. The threshold keeps the suite honest: a mutant
// that survives means a behavior no test pins down.
pitest {
    pitestVersion.set("1.30.1")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("dev.aomarai.minecraftmcp.core.*"))
    targetTests.set(listOf("dev.aomarai.minecraftmcp.core.*"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    // Known survivor: removing McpSyncServer.closeGracefully() in McpCore.close()
    // is unobservable because the Jetty stop that follows tears down the same
    // sessions. Everything else must be killed.
    mutationThreshold.set(98)
}
