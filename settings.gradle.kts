pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Fabric Loom is published here.
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
    }
}

rootProject.name = "minecraft-mcp"

include(
    "core",
    "adapters:fabric",
    "adapters:neoforge",
    "adapters:rcon",
    "stdio-shim",
)
