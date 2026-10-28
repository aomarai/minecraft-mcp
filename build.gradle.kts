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

    // Minecraft brings its own copies of these libraries onto the compile and test classpath, at the versions its
    // release was built with, and Dependabot flags those. None of them is in a release jar (the game supplies them
    // at runtime), so this only changes what we compile and run game tests against. The floors are the first versions
    // that fix the advisories listed in docs/security.md ("Dependency advisories"). The NeoForge nodes are left alone:
    // NeoForge declares the game's library versions as strict pins, and Gradle cannot resolve an override of those.
    if (!path.startsWith(":adapters:neoforge")) configurations.configureEach {
        resolutionStrategy.eachDependency {
            val v = requested.version.orEmpty()
            when {
                requested.group == "io.netty" && v.startsWith("4.1.") -> useVersion("4.1.137.Final")
                requested.group == "io.netty" && v.startsWith("4.2.") -> useVersion("4.2.17.Final")
                requested.group == "org.apache.logging.log4j" && v.startsWith("2.2") && v < "2.25.4" -> useVersion("2.25.4")
                requested.group == "org.apache.logging.log4j" && v.startsWith("2.26") -> useVersion("2.26.1")
                requested.group == "com.google.guava" && requested.name == "guava" -> useVersion("33.7.2-jre")
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> useVersion("3.18.0")
                requested.group == "org.apache.commons" && requested.name == "commons-compress" -> useVersion("1.28.0")
                requested.group == "org.jline" && requested.name == "jline-reader" -> useVersion("3.30.15")
                requested.group == "at.yawk.lz4" -> useVersion("1.11.4")
            }
        }
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.lz4:lz4-java")).using(module("at.yawk.lz4:lz4-java:1.11.4"))
                .because("org.lz4:lz4-java is unmaintained and has open advisories; at.yawk is the maintained fork")
        }
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
