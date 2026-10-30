plugins {
    alias(libs.plugins.pitest)
}

dependencies {
    api(libs.mcp.sdk)

    // The MCP SDK 2.1.0 pulls Jackson 3.1.4, which has open advisories fixed in 3.1.7 (docs/security.md,
    // "Dependency advisories"). The constraint is `api` so adapters and the shaded jars get the fixed version too.
    constraints {
        api(libs.jackson.core) { because("GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89") }
        api(libs.jackson.databind) { because("GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54, GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7") }
    }

    // The MCP SDK's Streamable HTTP server transport is servlet-based and
    // compiled against Jakarta Servlet 6.1. Jetty 12.1 EE11 provides that
    // servlet API and an embeddable container. Kept as `implementation` so
    // Jetty does not leak into adapters' compile classpaths.
    implementation(libs.jetty.servlet)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.slf4j.simple)
}

// The version the build was asked for, where McpVersion reads it: next to the class, so shaded jars carry it too.
val versionResource = tasks.register<WriteProperties>("versionResource") {
    group = "build"
    description = "Writes the build's version where McpVersion reads it."
    destinationFile.set(layout.buildDirectory.file("generated/version.properties"))
    property("version", project.version.toString())
}

tasks.processResources {
    from(versionResource) {
        into("dev/aomarai/minecraftmcp/core")
    }
}

// Mutation testing. Run with `./gradlew :core:pitest`; it is not part of
// `build` because it is slow. The threshold keeps the suite honest: a mutant
// that survives means a behavior no test pins down.
pitest {
    pitestVersion.set(libs.versions.pitest.engine)
    junit5PluginVersion.set(libs.versions.pitest.junit5.plugin)
    targetClasses.set(listOf("dev.aomarai.minecraftmcp.core.*"))
    targetTests.set(listOf("dev.aomarai.minecraftmcp.core.*"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutationThreshold.set(100)
}
