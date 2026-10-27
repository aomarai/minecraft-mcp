import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("net.neoforged.moddev") version "2.0.148"
    id("com.gradleup.shadow") version "9.6.1"
}

// This script is shared by every version node of the NeoForge adapter; versions/<node>/gradle.properties
// supplies what differs. See docs/version-matrix.md, "Adding a target".
val minecraftVersion = property("minecraft_version") as String
val neoForgeVersion = property("neoforge_version") as String
val neoForgeRange = property("neoforge_range") as String
val minecraftRange = property("minecraft_range") as String
val javaVersion = (property("java_version") as String).toInt()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

// Code that needs only vanilla Minecraft classes is shared by every loader adapter (adapters/common).
// Whatever the game renamed between 1.21.x and 26.x is behind GameCompat; a node builds the side it names.
sourceSets.main {
    java.srcDir(rootProject.file("adapters/common/src/main/java"))
    java.srcDir(rootProject.file("adapters/common/src/compat/${property("compat")}/java"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}

neoForge {
    version = neoForgeVersion
    mods {
        create("minecraft_mcp") {
            sourceSet(sourceSets.main.get())
        }
    }
    runs {
        create("server") {
            server()
            programArguments.add("--nogui")
        }
        // The game client, to prove the integrated server is served (scripts/smoke/neoforge-client-smoke.sh).
        create("client") {
            client()
            // -PquickPlay=<world> joins (or creates) that single-player world at start-up.
            providers.gradleProperty("quickPlay").orNull?.let { programArguments.addAll("--quickPlaySingleplayer", it) }
        }
    }
}

// The nodes prepare Minecraft one at a time (see the neoformRuntime service in the root build).
tasks.named("createMinecraftArtifacts") {
    usesService(gradle.sharedServices.registrations.getByName("neoformRuntime").service)
}

// Everything in `shade` is packed into the mod jar and relocated, so it cannot clash with
// another mod or with libraries the game ships.
val shade = configurations.create("shade")

dependencies {
    // The loader-agnostic core and everything it brings (MCP SDK, Jackson, Jetty, ...). The game
    // already provides SLF4J, so it stays out of the jar.
    shade(project(":core")) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    // Minecraft pins its own SLF4J (strictly 2.0.9), so core's newer one must not take part in resolution.
    implementation(project(":core")) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

tasks.processResources {
    val values = mapOf(
        "version" to project.version,
        "neoforge_range" to neoForgeRange,
        "minecraft_range" to minecraftRange,
    )
    inputs.properties(values)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(values)
    }
}

val relocationRoot = "dev.aomarai.minecraftmcp.shaded"

// minecraft-mcp-<version>+<minecraft>-neoforge.jar, as listed in docs/version-matrix.md
tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shade)
    archiveBaseName.set("minecraft-mcp")
    archiveVersion.set("${project.version}+$minecraftVersion-neoforge")
    archiveClassifier.set("")
    // Service files with the same name exist in several libraries and must be merged, not dropped.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // The SDK and its libraries are relocated as a whole: another mod may ship its own copy.
    listOf("io.modelcontextprotocol", "tools.jackson", "com.fasterxml.jackson", "com.networknt",
            "org.eclipse.jetty", "jakarta.servlet", "reactor", "org.reactivestreams",
            "org.yaml", "org.snakeyaml", "com.ethlo", "io.micrometer").forEach { relocate(it, "$relocationRoot.$it") }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

// The plain jar is not loadable (its dependencies are missing); only the shaded one is.
tasks.jar {
    archiveClassifier.set("dev")
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}

// Loader glue cannot be unit tested without a running game, so the line/branch coverage gate does
// not apply here. These classes are kept tiny (the logic lives in `core`, where the gate applies)
// and are exercised by the Docker smoke test, which starts a real NeoForge server.
tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
    enabled = false
}
