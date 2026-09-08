package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.exists

/**
 * The gate on the tests that drive the real Connect IQ SDK.
 *
 * They are opt-in rather than opt-out: a checkout on a machine without the SDK should go green, and
 * a red test there would be reporting the machine rather than the code.
 */
object LiveSdk {

    private const val ENABLED = "monkeyc.liveTests"

    /** The build passes this through; see the Test task in build.gradle.kts for why not the env. */
    val enabled: Boolean
        get() = System.getProperty(ENABLED).orEmpty() !in setOf("", "false")

    fun require(): ConnectIqSdk {
        assumeTrue(enabled, "run with -PliveTests to drive the real SDK")
        val sdk = ConnectIqSdk.detect()
        assumeTrue(sdk != null, "no Connect IQ SDK is installed")
        assumeTrue(sdk!!.hasLanguageServer, "the SDK at ${sdk.root} has no LanguageServer.jar")
        assumeTrue(
            sdk.version == null || sdk.version!! >= SdkVersion.LANGUAGE_SERVER_MINIMUM,
            "SDK ${sdk.version} is older than ${SdkVersion.LANGUAGE_SERVER_MINIMUM}",
        )
        return sdk
    }

    /** A device the machine actually has, preferring one the fixture declares. */
    fun device(sdk: ConnectIqSdk, preferred: List<String>): String {
        val installed = preferred.firstOrNull { sdk.devicesRoot.resolve(it).exists() }
        assumeTrue(installed != null, "none of $preferred is downloaded")
        return installed!!
    }

    /**
     * The fixture project, copied somewhere writable.
     *
     * A build writes `bin/` next to the sources, and the sources here live in the plugin's own test
     * resources; building in place would leave artifacts in the repository.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun fixture(into: Path, name: String = "fixture-app"): Path {
        val source = Path.of("src/test/resources/$name").toAbsolutePath()
        check(source.exists()) { "the fixture is missing from $source" }
        val target = into.resolve(name)
        source.copyToRecursively(target, followLinks = false)
        // Deliberately not the real path: on macOS a temp directory is reached through a symlink,
        // which is the case CanonicalPaths exists for and the language server test relies on.
        return target
    }
}
