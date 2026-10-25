plugins {
    id("info.solidsoft.pitest") version "1.19.0"
}

// The universal tier: RCON, Query and log tailing, with no Minecraft dependency at all. Unlike the mod
// adapters this module is plain Java, so the same coverage and mutation gates as `core` apply.
dependencies {
    api(project(":core"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Mutation testing; see core/build.gradle.kts.
pitest {
    pitestVersion.set("1.30.1")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("dev.aomarai.minecraftmcp.rcon.*"))
    targetTests.set(listOf("dev.aomarai.minecraftmcp.rcon.*"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutationThreshold.set(100)
}
