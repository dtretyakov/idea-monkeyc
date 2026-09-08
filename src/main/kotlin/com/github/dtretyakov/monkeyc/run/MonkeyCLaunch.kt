package com.github.dtretyakov.monkeyc.run

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

/** What a build produced, and what it was built from. */
data class BuiltArtifact(
    val sdk: ConnectIqSdk,
    val root: Path,
    val device: String,
    val output: Path,
    /** The compiler did not run: the output already matched the sources and the flags. */
    val upToDate: Boolean,
)

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
 * A build-only configuration stops after the first half.
 */
object MonkeyCLaunch {

    /** Compiles, and nothing else. */
    fun build(
        project: Project,
        options: MonkeyCRunOptions,
        target: String?,
        onProgress: (String) -> Unit,
    ): BuiltArtifact {
        val sdk = ConnectIqSdkService.getInstance().sdk
            ?: throw ExecutionException(
                "No Connect IQ SDK found. Install one with Garmin's SDK Manager, " +
                    "or set its location in Settings | Languages & Frameworks | Monkey C.",
            )

        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot()
            ?: throw ExecutionException("No Connect IQ project here: none of the content roots holds a manifest.xml.")

        val device = resolveDevice(project, model, root, options, target)
        val key = model.developerKey()
            ?: throw ExecutionException(
                "No developer key. Set one in Settings | Languages & Frameworks | Monkey C, " +
                    "or let the SDK Manager generate one.",
            )

        val simulator = !options.forDevice
        val output = when {
            options.kind.isTests -> ProjectLayout.testPrg(root, root.name, device)
            simulator -> ProjectLayout.appPrg(root, root.name)
            else -> ProjectLayout.devicePrg(root, root.name, device)
        }

        onProgress(if (simulator) "Building for $device..." else "Building for $device (device build)...")
        val result = MonkeyCBuildSession.run(
            project,
            BuildSpec(
                kind = options.kind.buildKind,
                root = root,
                output = output,
                jungleFiles = model.jungleFiles(root),
                device = device,
                simulator = simulator,
                developerKey = key,
                extraArguments = options.compilerArguments.split(Regex("\\s+")).filter { it.isNotEmpty() },
            ),
            title = title(options, device),
        )
        if (!result.succeeded) throw ExecutionException(MonkeyCBuilder.describeFailure(result))
        if (!output.exists()) throw ExecutionException("The build reported success but produced no $output.")

        return BuiltArtifact(sdk, root, device, output, result.upToDate)
    }

    /** Compiles, then makes sure there is a simulator to push the result into. */
    fun prepare(
        project: Project,
        options: MonkeyCRunOptions,
        target: String?,
        onProgress: (String) -> Unit,
    ): PreparedLaunch {
        val built = build(project, options, target, onProgress)

        if (options.forDevice) {
            throw ExecutionException(
                "This configuration builds for the watch, not for the simulator, so there is " +
                    "nothing to run here. Copy ${built.output.fileName} to GARMIN/APPS over USB.",
            )
        }

        onProgress("Starting the Connect IQ simulator...")
        if (!Simulator.start(built.sdk)) {
            throw ExecutionException(
                "The Connect IQ simulator did not start listening. " +
                    "Try starting it by hand from the SDK's bin directory.",
            )
        }

        return PreparedLaunch(
            sdk = built.sdk,
            root = built.root,
            device = built.device,
            prg = built.output,
            debugXml = ProjectLayout.debugXml(built.output),
            settingsJson = ProjectLayout.settingsJson(built.output),
        )
    }

    private fun title(options: MonkeyCRunOptions, device: String): String = when {
        options.kind.isTests -> "Building tests for $device"
        options.forDevice -> "Building for $device, for the watch"
        else -> "Building for $device"
    }

    /**
     * A Connect IQ executable is built for one device, so there is no such thing as running without
     * choosing one.
     *
     * The configuration's own choice wins, because a configuration that names a device was written
     * to mean it. Then the device chosen next to the Run button, then the project's default; and a
     * project that declares exactly one device does not need to be asked at all.
     */
    private fun resolveDevice(
        project: Project,
        model: MonkeyCProject,
        root: Path,
        options: MonkeyCRunOptions,
        target: String?,
    ): String {
        options.device.takeIf { it.isNotEmpty() }?.let { return it }
        target?.takeIf { it.isNotEmpty() }?.let { return it }
        MonkeyCSettings.getInstance(project).targetDevice.takeIf { it.isNotEmpty() }?.let { return it }

        val buildable = model.buildableDevices(root)
        if (buildable.size == 1) return buildable.first().id

        throw ExecutionException(
            if (buildable.isEmpty()) {
                "None of the devices this project declares is downloaded. Get them with the SDK Manager."
            } else {
                "Choose a device next to the Run button, or set the project's target device in " +
                    "Settings | Languages & Frameworks | Monkey C."
            },
        )
    }
}
