import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("fabric-loom") version "1.17.21"
    id("com.gradleup.shadow") version "9.6.1"
}

// This script is shared by every version node of the Fabric adapter; versions/<node>/gradle.properties
// supplies what differs. See docs/version-matrix.md, "Adding a target".
val minecraftVersion = property("minecraft_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val javaVersion = (property("java_version") as String).toInt()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

// Code that needs only vanilla Minecraft classes is shared by every loader adapter (adapters/common).
sourceSets.main {
    java.srcDir(rootProject.file("adapters/common/src/main/java"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}

// Everything in `shade` is packed into the mod jar and relocated, so it cannot clash with
// another mod or with libraries the game ships.
val shade = configurations.create("shade")

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // The loader-agnostic core and everything it brings (MCP SDK, Jackson, Jetty, ...). The game
    // already provides SLF4J, so it stays out of the jar.
    shade(project(":core")) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(project(":core"))
}

// Game tests run in a real dedicated server with fake players (`./gradlew :adapters:fabric:1.21.1:runGameTest`).
// `eula` accepts the Minecraft EULA for that disposable development server only.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "minecraft-mcp-gametest"
        eula = true
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val relocationRoot = "dev.aomarai.minecraftmcp.shaded"

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shade)
    archiveClassifier.set("dev-shaded")
    // Service files with the same name exist in several libraries and must be merged, not dropped.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // The SDK and its libraries are relocated as a whole: another mod may ship its own copy.
    listOf("io.modelcontextprotocol", "tools.jackson", "com.fasterxml.jackson", "com.networknt",
            "org.eclipse.jetty", "jakarta.servlet", "reactor", "org.reactivestreams",
            "org.yaml", "org.snakeyaml", "com.ethlo", "io.micrometer").forEach { relocate(it, "$relocationRoot.$it") }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.remapJar {
    dependsOn(shadowJar)
    inputFile.set(shadowJar.flatMap { it.archiveFile })
    // minecraft-mcp-<version>+<minecraft>-fabric.jar, as listed in docs/version-matrix.md
    archiveBaseName.set("minecraft-mcp")
    archiveVersion.set("${project.version}+$minecraftVersion-fabric")
}

// The plain jar is not loadable (its dependencies are missing); only the remapped, shaded one is.
tasks.jar {
    archiveClassifier.set("dev")
}

// Loader glue cannot be unit tested without a running game, so the line/branch coverage gate does
// not apply here. These classes are kept tiny (the logic lives in `core`, where the gate applies)
// and are exercised by the Docker smoke test, which starts a real Fabric server.
tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
    enabled = false
}
