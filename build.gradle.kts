import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
        // The test tree: SMTRunnerConsoleView and the service messages it is driven by. Bundled
        // with every IDE, but in an implementation-detail plugin, so it has to be asked for.
        bundledModule("intellij.platform.testRunner")
        bundledModule("intellij.platform.smRunner")
        pluginVerifier()
        // Gives the tests a real Project, so the parts that only exist inside an IDE — the
        // manifest form editor, for one — can be exercised rather than only compiled.
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junitJupiter)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testRuntimeOnly(libs.junitVintage)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The live tests drive the real SDK; they opt in rather than out so a clean checkout on a
    // machine without Connect IQ still goes green. A Gradle property rather than only an
    // environment variable, because a test JVM inherits the *daemon's* environment and not the
    // one the developer typed the command in — which shows up as the whole live suite silently
    // skipping.
    systemProperty(
        "monkeyc.liveTests",
        providers.gradleProperty("liveTests")
            // `-PliveTests` with no value is the natural way to type it, and arrives as "".
            .map { it.ifEmpty { "true" } }
            .orElse(providers.environmentVariable("MONKEYC_LIVE_TESTS").map { if (it == "1") "true" else it })
            .getOrElse("false"),
    )
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

        ides {
            // The IDEs JetBrains recommends are the thorough answer and cost several gigabytes:
            // one download each, on top of the one the build already has, which is more than a CI
            // runner's disk. So the default is the version this plugin is compiled against, which
            // is the check that has to pass, and the sweep is opt-in:
            //
            //     ./gradlew verifyPlugin -PverifyRecommended
            //     ./gradlew verifyPlugin -PverifyAgainst=/path/to/idea-2026.2.2   (already unpacked)
            val unpacked = providers.gradleProperty("verifyAgainst").orNull?.takeIf { it.isNotBlank() }
            when {
                unpacked != null -> local(file(unpacked))
                providers.gradleProperty("verifyRecommended").isPresent -> recommended()
                // The IDE the plugin is already compiled against: nothing extra to download, so it
                // fits on a runner's disk and reuses what the build step has cached.
                else -> current()
            }
        }
    }

    pluginConfiguration {
        id = "com.github.dtretyakov.monkeyc"
        name = "Monkey C (Garmin Connect IQ)"
        version = project.version.toString()

        // The top section of CHANGELOG.md, so the release notes are written once and in the place
        // a reader of the repository looks for them.
        changeNotes = provider { latestChangeNotes(file("CHANGELOG.md")) }

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
            jvmArgumentProviders.add(
                CommandLineArgumentProvider {
                    // The LSP client's own logging, in idea.log rather than only in the LSP
                    // console: what the server was asked and what it answered is the first thing
                    // worth knowing when a feature does nothing.
                    listOf("-Didea.log.debug.categories=#com.redhat.devtools.lsp4ij")
                },
            )
        }
    }
}

/**
 * The most recent section of a keep-a-changelog file, as the HTML the Marketplace renders.
 *
 * Only what the plugin descriptor allows: headings, lists and inline code. A Markdown library for
 * four constructs would be a dependency for the sake of one string.
 */
fun latestChangeNotes(changelog: File): String {
    if (!changelog.exists()) return ""

    val lines = changelog.readLines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) return ""
    val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## " ) }
        .let { if (it < 0) lines.size else start + 1 + it }

    val html = StringBuilder()
    var inList = false

    fun closeList() {
        if (inList) {
            html.append("</ul>")
            inList = false
        }
    }

    // A bullet may be wrapped over several lines; each continuation belongs to the item above it.
    lines.subList(start + 1, end).forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("### ") -> {
                closeList()
                html.append("<h4>").append(inlineHtml(line.removePrefix("### "))).append("</h4>")
            }
            line.startsWith("- ") -> {
                if (!inList) {
                    html.append("<ul>")
                    inList = true
                }
                html.append("<li>").append(inlineHtml(line.removePrefix("- ")))
            }
            inList -> html.append(' ').append(inlineHtml(line))
            else -> html.append("<p>").append(inlineHtml(line)).append("</p>")
        }
    }
    closeList()
    return html.toString()
}

fun inlineHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
