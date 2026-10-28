plugins {
    id("info.solidsoft.pitest") version "1.19.0"
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    // Only the SDK's JSON mapper is used, to parse and re-serialize JSON-RPC messages.
    implementation("io.modelcontextprotocol.sdk:mcp:2.1.0")
    constraints {
        implementation("tools.jackson.core:jackson-core:3.1.7") { because("GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89") }
        implementation("tools.jackson.core:jackson-databind:3.1.7") { because("GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54, GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7") }
    }

    // The integration test runs the shim against the real core server.
    testImplementation(project(":core"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.20")
}

// The launcher entry point reads its configuration from the environment, so the tests that run it
// need a token in theirs. This is a throwaway test value, not a secret.
val testToken = "test-token-for-the-main-entry-point"

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    environment("MINECRAFT_MCP_TOKEN", testToken)
}

// Mutation testing; see core/build.gradle.kts.
pitest {
    pitestVersion.set("1.30.1")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("dev.aomarai.minecraftmcp.shim.*"))
    targetTests.set(listOf("dev.aomarai.minecraftmcp.shim.*"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutationThreshold.set(100)
}

// The mutation run forks its own JVM, which needs the same environment.
tasks.withType<info.solidsoft.gradle.pitest.PitestTask>().configureEach {
    environment.put("MINECRAFT_MCP_TOKEN", testToken)
}

// The standalone jar an MCP host launches over stdio: `java -jar minecraft-mcp-<version>-stdio-shim.jar`.
// It carries the SDK's JSON mapper (and what that needs), so it runs on its own on any Java 21.
tasks.shadowJar {
    archiveBaseName.set("minecraft-mcp")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("stdio-shim")
    manifest {
        attributes("Main-Class" to "dev.aomarai.minecraftmcp.shim.StdioShim")
    }
    // Service files with the same name exist in several libraries and must be merged, not dropped.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
