package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.build.BuildSpec
import com.github.dtretyakov.monkeyc.build.CompilerCommand
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import com.github.dtretyakov.monkeyc.sdk.NewProject
import com.github.dtretyakov.monkeyc.sdk.ProjectGenerator
import com.github.dtretyakov.monkeyc.sdk.ProjectInfo
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Generates a project from the SDK's own template and then compiles it.
 *
 * Compiling is the whole point. The generator fills in a manifest by string replacement, renames
 * source files, and invents an app id — a plausible-looking project that the compiler rejects would
 * be the worst thing the wizard could produce, and only the compiler can say.
 */
class ProjectGeneratorLiveTest {

    @Test
    fun `a generated watch face compiles`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val device = LiveSdk.device(sdk, listOf("fenix7", "fenix6"))
        val info = ProjectInfo.read(sdk)
        assertNotNull(info, "the SDK has no projectInfo.xml")

        val template = info!!.templatesFor("watchface").first()
        val appType = info.appTypes.first { it.id == "watchface" }
        val minApiLevel = info.apiLevelsFor(appType).first { it.raw == "3.2.0" }

        val root = ProjectGenerator.generate(
            sdk,
            info,
            NewProject(
                // A space and a hyphen, so the sanitising is exercised rather than assumed.
                name = "Sun-rise Face",
                directory = temp.resolve("Sun-riseFace"),
                appType = appType,
                template = template,
                minApiLevel = minApiLevel,
                devices = listOf(device),
            ),
        )

        assertTrue(root.resolve("manifest.xml").exists())
        assertTrue(root.resolve("monkey.jungle").exists())
        assertTrue(
            root.resolve("source/Sun-riseFaceApp.mc").exists(),
            "source files are named after the project: " + root.resolve("source").toFile().list()?.toList(),
        )

        val manifest = root.resolve("manifest.xml").readText()
        assertTrue(manifest.contains("""type="watchface""""), manifest)
        assertTrue(manifest.contains("""<iq:product id="$device"/>"""), manifest)
        assertTrue(manifest.contains("""entry="Sun_riseFaceApp""""), "the entry class is the sanitised name")
        // The class in the source has to be the one the manifest names, or the app will not start.
        assertTrue(root.resolve("source/Sun-riseFaceApp.mc").readText().contains("class Sun_riseFaceApp"))

        val output = build(sdk, root, device)
        assertEquals(0, output.second, output.first)
        assertTrue(ProjectLayout.appPrg(root, root.fileName.toString()).exists())
    }

    /**
     * Every kind of project the wizard offers, generated and then compiled.
     *
     * The barrel is the one this exists for. Garmin's own VS Code wizard emits a barrel manifest
     * that will not build until a field is deleted by hand — it is on their bug list — and a
     * wizard that produces something the compiler rejects is the worst thing a wizard can do,
     * because the user has no reason to suspect the wizard. The only witness is the compiler, so
     * it is asked about each one.
     */
    @Test
    fun `every kind of project the wizard offers compiles`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val info = ProjectInfo.read(sdk)
        assertNotNull(info, "the SDK has no projectInfo.xml")

        val catalogue = DeviceCatalog(sdk.devicesRoot).devices()
        val failures = mutableListOf<String>()
        val checked = mutableListOf<String>()
        info!!.appTypes.forEach { appType ->
            val template = info.templatesFor(appType.id).firstOrNull() ?: return@forEach

            // The newest device that runs this kind of app, and then the newest API level that
            // device supports. Choosing them the other way round produces a pair the compiler
            // rejects — which is a real thing a user can do, and now has its own check in the
            // wizard, but is not what this test is about.
            val candidate = catalogue
                .filter { appType.isBarrel || it.supports(appType.id) }
                .maxByOrNull { it.sdkVersion ?: SdkVersion.parse("0.0.0")!! }
                ?: return@forEach
            val device = candidate.id
            val minApiLevel = info.apiLevelsFor(appType)
                .filter { candidate.sdkVersion == null || it <= candidate.sdkVersion!! }
                .maxOrNull()
                ?: return@forEach

            val root = ProjectGenerator.generate(
                sdk,
                info,
                NewProject(
                    name = "Check${appType.id.replace("-", "")}",
                    directory = temp.resolve(appType.id),
                    appType = appType,
                    template = template,
                    minApiLevel = minApiLevel,
                    devices = listOf(device),
                ),
            )

            val (output, exitCode) = if (appType.isBarrel) buildBarrel(sdk, root) else build(sdk, root, device)
            checked += appType.id
            if (exitCode != 0) failures += "${appType.id}: $output"
        }

        // Every branch above can skip, and a test that skipped everything would pass while
        // proving nothing. The barrel is named because it is the one this test exists for.
        assertTrue(checked.contains("barrel"), "the barrel template was never built; checked $checked")
        assertTrue(checked.size >= 4, "only $checked were built")

        assertTrue(failures.isEmpty(), "the wizard produced projects the compiler rejects:\n" + failures.joinToString("\n"))
    }

    private fun buildBarrel(sdk: com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk, root: Path): Pair<String, Int> {
        val arguments = CompilerCommand.arguments(
            sdk,
            JavaLocator.resolve(null),
            MonkeyCSettings(),
            BuildSpec(
                kind = BuildKind.BARREL,
                root = root,
                output = ProjectLayout.barrel(root, root.fileName.toString()),
                jungleFiles = ProjectLayout.jungleFiles(root, null),
            ),
        )
        val process = ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "the compiler did not finish" }
        return text to process.exitValue()
    }

    private fun build(sdk: com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk, root: Path, device: String): Pair<String, Int> {
        val arguments = CompilerCommand.arguments(
            sdk,
            JavaLocator.resolve(null),
            MonkeyCSettings(),
            BuildSpec(
                kind = BuildKind.APP,
                root = root,
                output = ProjectLayout.appPrg(root, root.fileName.toString()),
                jungleFiles = ProjectLayout.jungleFiles(root, null),
                device = device,
                developerKey = sdk.defaultDeveloperKey,
            ),
        )
        val process = ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "the compiler did not finish" }
        return text to process.exitValue()
    }
}
