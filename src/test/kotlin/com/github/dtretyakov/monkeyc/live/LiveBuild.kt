package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.build.BuildSpec
import com.github.dtretyakov.monkeyc.build.CompilerCommand
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the real compiler over a fixture, through the same argument builder the IDE uses.
 *
 * Shared rather than duplicated because more than one live test needs a `.prg` before it can start:
 * the compiler tests care about the output, the debugger test only wants something to launch.
 */
object LiveBuild {

    class Output(val exitCode: Int, val text: String, val prg: Path)

    fun run(
        sdk: ConnectIqSdk,
        project: Path,
        device: String,
        settings: MonkeyCSettings = MonkeyCSettings(),
        kind: BuildKind = BuildKind.APP,
    ): Output {
        val name = project.fileName.toString()
        val prg = when (kind) {
            BuildKind.TESTS, BuildKind.BARREL_TESTS -> ProjectLayout.testPrg(project, name, device)
            BuildKind.BARREL -> ProjectLayout.barrel(project, name)
            BuildKind.EXPORT -> ProjectLayout.exportIq(project, name)
            BuildKind.APP -> ProjectLayout.appPrg(project, name)
        }

        val arguments = CompilerCommand.arguments(
            sdk,
            JavaLocator.resolve(null),
            settings,
            BuildSpec(
                kind = kind,
                root = project,
                output = prg,
                jungleFiles = ProjectLayout.jungleFiles(project, null),
                device = device,
                simulator = true,
                developerKey = sdk.defaultDeveloperKey.takeIf { kind.needsDeveloperKey },
            ),
        )

        val process = ProcessBuilder(arguments)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "the compiler did not finish" }
        return Output(process.exitValue(), text, prg)
    }
}
