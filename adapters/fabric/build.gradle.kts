import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    // Loom is applied below, because which of its two plugin ids a node needs depends on the node.
    alias(libs.plugins.loom) apply false
    alias(libs.plugins.shadow)
}

// This script is shared by every version node of the Fabric adapter; versions/<node>/gradle.properties
// supplies what differs. See docs/version-matrix.md, "Adding a target".
val minecraftVersion = property("minecraft_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val javaVersion = (property("java_version") as String).toInt()
val minecraftRange = property("minecraft_range") as String
val javaRange = ">=$javaVersion"
// Minecraft up to 1.21.x ships obfuscated and needs Loom's remapping plugin; 26.1 and later ship with
// Mojang's names, so the plain plugin is used and there is nothing to remap.
val remap = (property("remap") as String).toBoolean()
val gameTests = (property("game_tests") as String).toBoolean()

apply(plugin = if (remap) "fabric-loom" else "net.fabricmc.fabric-loom")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

// Code that needs only vanilla Minecraft classes is shared by every loader adapter (adapters/common).
// Whatever the game renamed between 1.21.x and 26.x is behind GameCompat; a node builds the side it names.
sourceSets.main {
    java.srcDir(rootProject.file("adapters/common/src/main/java"))
    java.srcDir(rootProject.file("adapters/common/src/compat/${property("compat")}/java"))
    // The client's own endpoint (screenshots); the same jar serves a dedicated server, which never loads it.
    java.srcDir(rootProject.file("adapters/fabric/src/client/java"))
    java.srcDir(rootProject.file("adapters/fabric/src/clientcompat/${property("compat")}/java"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}

// Everything in `shade` is packed into the mod jar and relocated, so it cannot clash with
// another mod or with libraries the game ships.
val shade = configurations.create("shade")

// The Loom plugin is applied by id above, so its configurations and extensions are reached by name.
val loom = extensions.getByName("loom") as net.fabricmc.loom.api.LoomGradleExtensionAPI
val game = if (remap) "modImplementation" else "implementation"

// The widener names the game's classes in the node's own namespace: Mojang's for 1.21.x (remapped), official for 26.x.
val accessWidenerDir = rootProject.file("adapters/fabric/src/accesswidener/${property("compat")}")
sourceSets.main { resources.srcDir(accessWidenerDir) }
loom.accessWidenerPath.set(accessWidenerDir.resolve("minecraft-mcp.accesswidener"))

dependencies {
    add("minecraft", "com.mojang:minecraft:$minecraftVersion")
    if (remap) {
        add("mappings", loom.officialMojangMappings())
    }
    add(game, "net.fabricmc:fabric-loader:$fabricLoaderVersion")
    add(game, "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // The loader-agnostic core and everything it brings (MCP SDK, Jackson, Jetty, ...). The game
    // already provides SLF4J, so it stays out of the jar.
    shade(project(":core")) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(project(":core"))
}

// Game tests run in a real dedicated server with fake players (`./gradlew :adapters:fabric:1.21.1:runGameTest`).
// `eula` accepts the Minecraft EULA for that disposable development server only.
if (gameTests) {
    extensions.configure<net.fabricmc.loom.api.fabricapi.FabricApiExtension>("fabricApi") {
        configureTests {
            createSourceSet = true
            modId = "minecraft-mcp-gametest"
            eula = true
        }
    }
}

tasks.processResources {
    val values = mapOf("version" to project.version, "minecraft_range" to minecraftRange, "java_range" to javaRange)
    inputs.properties(values)
    filesMatching("fabric.mod.json") {
        expand(values)
    }
}

val relocationRoot = "dev.aomarai.minecraftmcp.shaded"

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shade)
    // With nothing to remap, the shaded jar is the final one.
    if (remap) {
        archiveClassifier.set("dev-shaded")
    } else {
        archiveClassifier.set("")
        archiveBaseName.set("minecraft-mcp")
        archiveVersion.set("${project.version}+$minecraftVersion-fabric")
    }
    // Service files with the same name exist in several libraries and must be merged, not dropped.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // The SDK and its libraries are relocated as a whole: another mod may ship its own copy.
    listOf("io.modelcontextprotocol", "tools.jackson", "com.fasterxml.jackson", "com.networknt",
            "org.eclipse.jetty", "jakarta.servlet", "reactor", "org.reactivestreams",
            "org.yaml", "org.snakeyaml", "com.ethlo", "io.micrometer").forEach { relocate(it, "$relocationRoot.$it") }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

// minecraft-mcp-<version>+<minecraft>-fabric.jar, as listed in docs/version-matrix.md
if (remap) {
    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        dependsOn(shadowJar)
        inputFile.set(shadowJar.flatMap { it.archiveFile })
        archiveBaseName.set("minecraft-mcp")
        archiveVersion.set("${project.version}+$minecraftVersion-fabric")
    }
} else {
    tasks.assemble {
        dependsOn(shadowJar)
    }
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
