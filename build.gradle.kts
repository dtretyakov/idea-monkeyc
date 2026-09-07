import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    // Version comes from the settings plugin, which already has IPGP on the classpath.
    id("org.jetbrains.intellij.platform")
}

group = "com.github.dtretyakov.monkeyc"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.ideaVersion)
        plugin("com.redhat.devtools.lsp4ij:${libs.versions.lsp4ij.get()}")
        pluginVerifier()
    }

    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The live tests drive the real SDK; they opt in rather than out so a clean
    // checkout on a machine without Connect IQ still goes green.
    environment("MONKEYC_LIVE_TESTS", providers.environmentVariable("MONKEYC_LIVE_TESTS").getOrElse(""))
    testLogging {
        showStandardStreams = providers.gradleProperty("showOutput").isPresent
    }
}

intellijPlatform {
    pluginVerification {
        failureLevel = FailureLevel.ALL - setOf(
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
        )
    }

    pluginConfiguration {
        id = "com.github.dtretyakov.monkeyc"
        name = "Monkey C (Garmin Connect IQ)"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "252"
            // Deliberately open-ended: the plugin uses no unstable platform API,
            // and pinning it would break every IDE upgrade for no reason.
            untilBuild = provider { null }
        }
    }
}

/**
 * A sandbox IDE with a Connect IQ project already open, so a change can be looked at rather than
 * only compiled.
 *
 *     ./gradlew runIdeWithFixture
 */
intellijPlatformTesting {
    /**
     * A headless IDE that checks the plugin is whole: extensions registered, file types bound,
     * the SDK reachable. See SelfCheckStarter for why a unit test cannot answer any of that.
     *
     *     ./gradlew runSelfCheck
     */
    runIde.register("runSelfCheck") {
        task {
            args = listOf("monkeyCSelfCheck")
            jvmArgumentProviders.add(
                CommandLineArgumentProvider { listOf("-Djava.awt.headless=true", "-Didea.is.internal=true") },
            )
        }
    }

    runIde.register("runIdeWithFixture") {
        task {
            args = listOf(
                providers.gradleProperty("fixture")
                    .getOrElse(layout.projectDirectory.dir("src/test/resources/fixture-app").asFile.absolutePath),
            )
        }
    }
}
