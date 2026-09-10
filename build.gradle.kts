import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    // Version comes from the settings plugin, which already has IPGP on the classpath.
    id("org.jetbrains.intellij.platform")
}

group = "com.github.dtretyakov.monkeyc"
version = providers.gradleProperty("pluginVersion").get()

/**
 * The oldest IDE the plugin promises to run in, spelled as a version rather than a build number.
 *
 * It has to agree with `sinceBuild` below: that is the promise, and this is the IDE the promise is
 * checked against. 2026.1 because that is what the verifier passes on, and no further back:
 * `com.intellij.build.FilePosition`, `ComponentPanelBuilder`, `ProjectLevelVcsManager` and
 * `runReadActionBlocking` all changed shape between 2025.2 and here, and `sinceBuild` said 252
 * for a while — a promise that would have met a user on 2025.2 as a NoSuchMethodError the first
 * time the compiler reported a warning.
 */
val OLDEST_SUPPORTED_IDE = "2026.1"

/**
 * The same fact as a build number, which is the only spelling `sinceBuild` accepts.
 *
 * Derived rather than written twice: the two drifting apart means the plugin promises one IDE and
 * is checked against another, and nothing would say so.
 */
val OLDEST_SUPPORTED_BUILD =
    OLDEST_SUPPORTED_IDE.split(".").let { (year, release) -> year.takeLast(2) + release }

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
            //
            // And the one the Marketplace will run whatever we do, because `sinceBuild` promises
            // it: the oldest IDE the plugin claims to support. It is compiled against the newest,
            // so nothing but this check can tell us the promise is true.
            //
            //     ./gradlew verifyPlugin -PverifySince
            //
            // And two more, each a single IDE rather than a set:
            //
            //     ./gradlew verifyPlugin -PverifyEap             the next IDEA, before it ships
            //     ./gradlew verifyPlugin -PverifyAndroidStudio   the newest stable Android Studio
            //
            // The EAP is inside the recommended sweep already — `./gradlew printProductsReleases`
            // lists what that resolves to — but the sweep is every IDE in range and this is one,
            // which is the difference between a check you run while waiting and one you schedule.
            // Android Studio is not in the sweep at any price: `recommended()` only ever picks the
            // product the plugin is built against. It is on a release train of its own, months
            // behind the platform under it, and a plugin that asks only for
            // `com.intellij.modules.platform` is offered to it whether or not anybody checked.
            val unpacked = providers.gradleProperty("verifyAgainst").orNull?.takeIf { it.isNotBlank() }
            when {
                unpacked != null -> local(file(unpacked))
                providers.gradleProperty("verifyRecommended").isPresent -> recommended()
                providers.gradleProperty("verifySince").isPresent ->
                    create(IntelliJPlatformType.IntellijIdeaUltimate, OLDEST_SUPPORTED_IDE)
                providers.gradleProperty("verifyEap").isPresent -> latest {
                    types = listOf(IntelliJPlatformType.IntellijIdeaUltimate)
                    channels = listOf(ProductRelease.Channel.EAP)
                }
                providers.gradleProperty("verifyAndroidStudio").isPresent -> latest {
                    types = listOf(IntelliJPlatformType.AndroidStudio)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
                // The IDE the plugin is already compiled against: nothing extra to download, so it
                // fits on a runner's disk and reuses what the build step has cached.
                else -> current()
            }
        }
    }

    pluginConfiguration {
        id = "com.github.dtretyakov.monkeyc"
        // The Marketplace asks for one to four words and at most twenty characters, and it will
        // not list a name that leans on someone else's trademark. "Monkey C (Garmin Connect IQ)"
        // failed both: too long, and two of Garmin's marks in it. The language's name alone is how
        // every other language plugin is named, and what the plugin is for is the description's
        // job — which is where "Garmin Connect IQ" now says it, as a statement of compatibility.
        name = "Monkey C"
        version = project.version.toString()

        // The top section of CHANGELOG.md, so the release notes are written once and in the place
        // a reader of the repository looks for them.
        changeNotes = provider { latestChangeNotes(file("CHANGELOG.md")) }

        ideaVersion {
            sinceBuild = OLDEST_SUPPORTED_BUILD
            // Deliberately open-ended: the plugin uses no unstable platform API,
            // and pinning it would break every IDE upgrade for no reason.
            untilBuild = provider { null }
        }
    }

    /**
     * The signature the IDE checks when the plugin is installed.
     *
     * Not required by the Marketplace, which will accept an unsigned upload and sign it with its
     * own key. Done anyway, because an unsigned plugin installs behind a warning dialog, and the
     * first thing a developer sees should not be a question about whether to trust it. The
     * certificate is the author's and lives nowhere in this repository — see PUBLISHING.md.
     */
    signing {
        // As a file, though it arrives as content, and the difference is not cosmetic: given as
        // content, the platform plugin hands the chain to `verifyPluginSignature` twice — once as
        // the temporary file it writes for it, and again as raw arguments — and the zip signer
        // rejects the second copy with `Invalid argument: -----BEGIN CERTIFICATE-----`. Signing
        // itself is unaffected, so the failure lands at the very end of a release, after every
        // other check has passed. The chain is public material; it ships inside every signed
        // plugin, and writing it under `build/` exposes nothing that the artifact does not.
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN").map { chain ->
            layout.buildDirectory.file("signing/certificate-chain.pem").get().also {
                it.asFile.parentFile.mkdirs()
                it.asFile.writeText(chain)
            }
        }
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release goes to a channel of its own, so it reaches the people who added that
        // channel and nobody else. Garmin ships SDK betas and this plugin has to be able to
        // follow them without every user waking up to an untested build.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

/**
 * The dependency between signing and checking the signature, which nothing else declares.
 *
 * `verifyPluginSignature` reads the archive `signPlugin` writes, and the platform plugin hands the
 * path across as a plain location rather than as the provider that carries its producing task with
 * it. Gradle will not run a task whose input is another task's undeclared output, so the pair fails
 * the moment both are asked for at once — which is the only way either is ever asked for, here and
 * in PUBLISHING.md. It fails at the end of a release, after everything else has already passed.
 */
tasks.named("verifyPluginSignature") {
    dependsOn(tasks.named("signPlugin"))
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
            // A copy under `build`, not the fixture itself. The IDE opens the project it is given
            // and then writes to it — `bin/` from every build, `.idea/` the moment it opens — and
            // the fixture lives in the repository, so opening it in place meant a run left the
            // working tree dirty and a stray edit made while trying something out was one `git
            // add -A` away from being committed. Fresh each time, so what is opened is what the
            // repository says; `-Pfixture=/path/to/project` is the way to work in one that lasts.
            val scratch = layout.buildDirectory.dir("fixture-app").get().asFile
            val chosen = providers.gradleProperty("fixture").getOrElse(scratch.absolutePath)
            if (chosen == scratch.absolutePath) {
                doFirst {
                    val source = layout.projectDirectory.dir("src/test/resources/fixture-app").asFile
                    scratch.deleteRecursively()
                    source.copyRecursively(scratch)
                }
            }
            args = listOf(chosen)
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
    // Closed explicitly. A browser forgives an unclosed `<li>`; the Marketplace runs the change
    // notes through a sanitiser of its own, which is stricter than the plugin descriptor's DTD and
    // has no reason to be as forgiving.
    var openItem = false

    fun closeItem() {
        if (openItem) {
            html.append("</li>")
            openItem = false
        }
    }

    fun closeList() {
        closeItem()
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
                closeItem()
                if (!inList) {
                    html.append("<ul>")
                    inList = true
                }
                html.append("<li>").append(inlineHtml(line.removePrefix("- ")))
                openItem = true
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
