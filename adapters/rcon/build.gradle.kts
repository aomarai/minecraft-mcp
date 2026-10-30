plugins {
    alias(libs.plugins.pitest)
    alias(libs.plugins.shadow)
}

// The universal tier: RCON, Query and log tailing, with no Minecraft dependency at all. Unlike the mod
// adapters this module is plain Java, so the same coverage and mutation gates as `core` apply.
dependencies {
    api(project(":core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Mutation testing; see core/build.gradle.kts.
pitest {
    pitestVersion.set(libs.versions.pitest.engine)
    junit5PluginVersion.set(libs.versions.pitest.junit5.plugin)
    targetClasses.set(listOf("dev.aomarai.minecraftmcp.rcon.*"))
    targetTests.set(listOf("dev.aomarai.minecraftmcp.rcon.*"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutationThreshold.set(100)
}

// The standalone jar: `java -jar minecraft-mcp-<version>-universal.jar`. It runs in its own JVM, so nothing
// is relocated (there is no game or other mod to clash with). Named as docs/version-matrix.md lists it.
tasks.shadowJar {
    archiveBaseName.set("minecraft-mcp")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("universal")
    manifest {
        attributes("Main-Class" to "dev.aomarai.minecraftmcp.rcon.UniversalMain")
    }
    // Service files with the same name exist in several libraries and must be merged, not dropped.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
