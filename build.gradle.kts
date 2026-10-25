import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    id("org.sonarqube") version "7.5.0.8588"
}

sonar {
    properties {
        property("sonar.projectKey", "aomarai_minecraft-mcp_f6130f7f-7b1b-4688-9c1f-23c464302442")
        property("sonar.projectName", "minecraft-mcp")
    }
}

// Preparing Minecraft for a NeoForge node decompiles the game in a JVM that takes up to 4 GB. Every node
// does it, and several at once exhaust the memory of a CI runner (the build then dies without a message),
// so the NeoForge nodes take turns through this service.
abstract class OneAtATime : BuildService<BuildServiceParameters.None>

gradle.sharedServices.registerIfAbsent("neoformRuntime", OneAtATime::class) {
    maxParallelUsages = 1
}

// A release build passes the version of its tag (-PreleaseVersion=1.2.3, or ORG_GRADLE_PROJECT_releaseVersion), so
// the jar names and the version inside each jar's mod metadata agree. Everything else builds a snapshot.
val buildVersion = providers.gradleProperty("releaseVersion").map { it.trim() }.filter { it.isNotEmpty() }
    .getOrElse("0.1.0-SNAPSHOT")

allprojects {
    group = "dev.aomarai.minecraftmcp"
    version = buildVersion
}

// A Stonecutter controller (it has a stonecutter.gradle.kts) only holds the shared sources and build
// script; its version nodes below it do the building, so it gets no Java plugins of its own.
subprojects.filter { !it.file("stonecutter.gradle.kts").exists() }.forEach { subproject -> subproject.run {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        extensions.configure<JacocoTaskExtension> {
            isEnabled = true
        }
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    // Coverage gate: every line and branch of production code must be exercised.
    // Modules without classes (placeholder adapters) skip the check automatically.
    tasks.withType<JacocoCoverageVerification>().configureEach {
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "1.0".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = "1.0".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.withType<JacocoCoverageVerification>())
    }
} }
