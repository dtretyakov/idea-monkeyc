package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.build.BuildSpec
import com.github.dtretyakov.monkeyc.build.CompilerCommand
import com.github.dtretyakov.monkeyc.build.CompilerMessage
import com.github.dtretyakov.monkeyc.build.CompilerOutputParser
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Builds the fixture with the real compiler.
 *
 * This is the test that would catch a wrong flag: the arguments are assembled by the same code the
 * IDE uses, and then actually run. A unit test on the argument list can only say the list is what
 * it was yesterday.
 */
class CompilerLiveTest {

    @Test
    fun `builds an app, and writes the symbols the debugger needs`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val project = LiveSdk.fixture(temp)
        val device = LiveSdk.device(sdk, listOf("fenix7", "fenix6"))

        val output = build(sdk, project, device, MonkeyCSettings())

        assertEquals(0, output.exitCode, output.text)
        val prg = ProjectLayout.appPrg(project, project.fileName.toString())
        assertTrue(prg.exists(), "expected $prg")
        assertTrue(
            ProjectLayout.debugXml(prg).exists(),
            "the compiler writes the symbol file on every build, without being asked for it with -g",
        )
    }

    @Test
    fun `a broken source produces a diagnostic the parser can place`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val project = LiveSdk.fixture(temp)
        val device = LiveSdk.device(sdk, listOf("fenix7", "fenix6"))

        val source = project.resolve("source/FixtureApp.mc")
        source.writeText(source.readText().replace("counter += 1;", "counter += ;"))

        val output = build(sdk, project, device, MonkeyCSettings())

        assertTrue(output.exitCode != 0, "a syntax error must fail the build")
        val errors = CompilerOutputParser.parse(output.text)
            .filter { it.severity == CompilerMessage.Severity.ERROR }
        assertTrue(errors.isNotEmpty(), "no diagnostic was parsed out of:\n${output.text}")
        assertTrue(
            // Real paths: on macOS the compiler reports /private/var where the test sees /var.
            errors.any { it.file?.let { path -> Path.of(path).toRealPath() } == source.toRealPath() && it.line != null },
            "the diagnostic must name the file and line, so it can be clicked:\n$errors",
        )
    }

    @Test
    fun `type checking is only asked for when the SDK understands it`() {
        val sdk = LiveSdk.require()
        val settings = MonkeyCSettings().apply { typeCheckLevel = "Strict" }

        val arguments = CompilerCommand.arguments(
            sdk,
            JavaLocator.resolve(null),
            settings,
            BuildSpec(
                kind = BuildKind.APP,
                root = Path.of("/app"),
                output = Path.of("/app/bin/App.prg"),
                jungleFiles = listOf(Path.of("/app/monkey.jungle")),
                device = "fenix7",
                developerKey = Path.of("/key.der"),
            ),
        )

        assertEquals(sdk.supportsTypeChecking, arguments.contains("-l"))
        assertTrue(arguments.contains("fenix7_sim"), "a simulator build asks for the _sim variant")
    }

    private class Output(val exitCode: Int, val text: String)

    private fun build(sdk: ConnectIqSdk, project: Path, device: String, settings: MonkeyCSettings): Output {
        val prg = ProjectLayout.appPrg(project, project.fileName.toString())
        val arguments = CompilerCommand.arguments(
            sdk,
            JavaLocator.resolve(null),
            settings,
            BuildSpec(
                kind = BuildKind.APP,
                root = project,
                output = prg,
                jungleFiles = ProjectLayout.jungleFiles(project, null),
                device = device,
                simulator = true,
                developerKey = sdk.defaultDeveloperKey,
            ),
        )

        val process = ProcessBuilder(arguments)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "the compiler did not finish" }
        return Output(process.exitValue(), text)
    }
}
