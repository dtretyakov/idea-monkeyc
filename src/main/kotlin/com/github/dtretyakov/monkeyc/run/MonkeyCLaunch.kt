package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.build.BuildSpec
import com.github.dtretyakov.monkeyc.build.MonkeyCBuildSession
import com.github.dtretyakov.monkeyc.build.MonkeyCBuilder
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/** Everything the simulator or the debug adapter needs to start an app. */
data class PreparedLaunch(
    val sdk: ConnectIqSdk,
    val root: Path,
    val device: String,
    val prg: Path,
    val debugXml: Path,
    val settingsJson: Path?,
)

/**
 * Gets a project to the point where it can be launched: compiled, for a known device, with the
 * simulator up.
 *
 * Run and Debug both come through here, because they need exactly the same things — the difference
 * between them starts afterwards, at whether `monkeydo` or the debug adapter pushes the `.prg`.
 */
object MonkeyCLaunch {

    fun prepare(
        project: Project,
        options: MonkeyCRunOptions,
        onProgress: (String) -> Unit,
    ): PreparedLaunch {
        val sdk = ConnectIqSdkService.getInstance().sdk
            ?: throw ExecutionException(
                "No Connect IQ SDK found. Install one with Garmin's SDK Manager, " +
                    "or set its location in Settings | Languages & Frameworks | Monkey C.",
            )

        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot()
            ?: throw ExecutionException("No Connect IQ project here: none of the content roots holds a manifest.xml.")

        val device = resolveDevice(project, model, root, options)
        val key = model.developerKey()
            ?: throw ExecutionException(
                "No developer key. Set one in Settings | Languages & Frameworks | Monkey C, " +
                    "or let the SDK Manager generate one.",
            )

        val prg = if (options.runTests) {
            ProjectLayout.testPrg(root, root.name, device)
        } else {
            ProjectLayout.appPrg(root, root.name)
        }

        onProgress("Building for $device...")
        val result = MonkeyCBuildSession.run(
            project,
            BuildSpec(
                kind = if (options.runTests) BuildKind.TESTS else BuildKind.APP,
                root = root,
                output = prg,
                jungleFiles = model.jungleFiles(root),
                device = device,
                simulator = true,
                developerKey = key,
                extraArguments = options.compilerArguments.split(Regex("\\s+")).filter { it.isNotEmpty() },
            ),
            title = if (options.runTests) "Building tests for $device" else "Building for $device",
        )
        if (!result.succeeded) throw ExecutionException(MonkeyCBuilder.describeFailure(result))
        if (!prg.exists()) throw ExecutionException("The build reported success but produced no $prg.")

        onProgress("Starting the Connect IQ simulator...")
        if (!Simulator.start(sdk)) {
            throw ExecutionException(
                "The Connect IQ simulator did not start listening. " +
                    "Try starting it by hand from the SDK's bin directory.",
            )
        }

        return PreparedLaunch(
            sdk = sdk,
            root = root,
            device = device,
            prg = prg,
            debugXml = ProjectLayout.debugXml(prg),
            settingsJson = ProjectLayout.settingsJson(prg),
        )
    }

    /**
     * A Connect IQ executable is built for one device, so there is no such thing as running without
     * choosing one. The configuration's own choice wins, then the project's; a project that
     * declares exactly one device does not need to be asked.
     */
    private fun resolveDevice(
        project: Project,
        model: MonkeyCProject,
        root: Path,
        options: MonkeyCRunOptions,
    ): String {
        options.device.takeIf { it.isNotEmpty() }?.let { return it }
        MonkeyCSettings.getInstance(project).targetDevice.takeIf { it.isNotEmpty() }?.let { return it }

        val buildable = model.buildableDevices(root)
        if (buildable.size == 1) return buildable.first().id

        throw ExecutionException(
            if (buildable.isEmpty()) {
                "None of the devices this project declares is downloaded. Get them with the SDK Manager."
            } else {
                "Choose a device for this run configuration, or set the project's target device in " +
                    "Settings | Languages & Frameworks | Monkey C."
            },
        )
    }
}
