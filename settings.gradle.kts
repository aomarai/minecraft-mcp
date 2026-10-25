pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Fabric Loom is published here.
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        // Stonecutter (multi-version builds) is published here.
        maven("https://maven.kikugie.dev/releases") {
            name = "Stonecutter"
        }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// One controller per loader; each builds its version nodes from the controller's own src/ and
// build.gradle.kts. Node versions live in <controller>/versions/<node>/gradle.properties.
stonecutter {
    create(":adapters:fabric") {
        version("1.21.1")
        version("26.2")
        version("26.3")
        vcsVersion = "1.21.1"
    }
    create(":adapters:neoforge") {
        version("1.21.1")
        version("26.2")
        version("26.3")
        vcsVersion = "1.21.1"
    }
}

rootProject.name = "minecraft-mcp"

include(
    "core",
    "adapters:rcon",
    "stdio-shim",
)
