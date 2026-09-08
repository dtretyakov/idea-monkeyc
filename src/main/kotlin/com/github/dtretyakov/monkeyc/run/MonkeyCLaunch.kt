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
    /** The other half of a complication pair, built for the same device. */
    val paired: BuiltArtifact? = null,
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

        checkKindSuitsProject(model, root, options.kind)

        val kind = options.kind
        val device = if (kind.buildKind.needsDevice) resolveDevice(project, model, root, options, target) else ""
        // A barrel is unsigned, so it is the one kind that can be built without a key at all.
        val key = model.developerKey()
        if (key == null && kind.buildKind.needsDeveloperKey) {
            throw ExecutionException(
                "No developer key. Set one in Settings | Languages & Frameworks | Monkey C, " +
                    "or let the SDK Manager generate one.",
            )
        }

        val simulator = !options.forDevice
        val output = outputFor(options, root, device)

        val title = title(options, device)
        onProgress("$title...")
        val result = MonkeyCBuildSession.run(
            project,
            BuildSpec(
                kind = options.kind.buildKind,
                root = root,
                output = output,
                jungleFiles = model.jungleFiles(root),
                device = device.takeIf { it.isNotEmpty() },
                simulator = simulator,
                developerKey = key,
                extraArguments = options.compilerArguments.split(Regex("\\s+")).filter { it.isNotEmpty() },
            ),
            title = title,
        )
        if (!result.succeeded) throw ExecutionException(MonkeyCBuilder.describeFailure(result))
        if (!output.exists()) throw ExecutionException("The build reported success but produced no $output.")

        return BuiltArtifact(sdk, root, device, output, result.upToDate)
    }

    /**
     * The other app of a complication pair, built for the same device.
     *
     * A complication is two apps that only make sense together — one publishes a value, the other
     * shows it — and the simulator can hold both at once. The second one is a whole Connect IQ
     * project of its own, so it is compiled the same way the first was.
     */
    private fun buildPaired(
        project: Project,
        options: MonkeyCRunOptions,
        device: String,
        onProgress: (String) -> Unit,
    ): BuiltArtifact? {
        val configured = options.pairedProject.trim().takeIf { it.isNotEmpty() } ?: return null
        val root = Path.of(configured)
        if (!ProjectLayout.isProjectRoot(root)) {
            throw ExecutionException("The paired app at $root has no manifest.xml, so it is not a Connect IQ project.")
        }

        val sdk = ConnectIqSdkService.getInstance().sdk ?: throw ExecutionException("No Connect IQ SDK found.")
        val model = MonkeyCProject.getInstance(project)
        val output = ProjectLayout.appPrg(root, root.name)

        onProgress("Building the paired app ${root.name} for $device...")
        val result = MonkeyCBuildSession.run(
            project,
            BuildSpec(
                kind = BuildKind.APP,
                root = root,
                output = output,
                jungleFiles = ProjectLayout.jungleFiles(root, null),
                device = device,
                simulator = true,
                developerKey = model.developerKey(),
            ),
            title = "Building ${root.name} for $device",
        )
        if (!result.succeeded) {
            throw ExecutionException("The paired app did not build: ${MonkeyCBuilder.describeFailure(result)}")
        }

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
        val paired = buildPaired(project, options, built.device, onProgress)

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
            paired = paired,
        )
    }

    /**
     * Where the artifact goes.
     *
     * Runnable output lives in `bin`, where the simulator and the debugger already look for it by
     * name; an export or a barrel is something the developer takes away, so it goes to `out` and
     * the configuration may say otherwise.
     */
    private fun outputFor(options: MonkeyCRunOptions, root: Path, device: String): Path {
        val chosen = options.outputPath.trim().takeIf { it.isNotEmpty() }?.let {
            val path = Path.of(it)
            if (path.isAbsolute) path else root.resolve(path)
        }
        return when (options.kind) {
            MonkeyCRunKind.EXPORT -> chosen ?: ProjectLayout.exportIq(root, root.name)
            MonkeyCRunKind.BARREL -> chosen ?: ProjectLayout.barrel(root, root.name)
            MonkeyCRunKind.TESTS, MonkeyCRunKind.BARREL_TESTS -> ProjectLayout.testPrg(root, root.name, device)
            MonkeyCRunKind.APP -> ProjectLayout.appPrg(root, root.name)
            MonkeyCRunKind.BUILD ->
                if (options.forDevice) {
                    ProjectLayout.devicePrg(root, root.name, device)
                } else {
                    ProjectLayout.appPrg(root, root.name)
                }
        }
    }

    /**
     * A barrel is not an app, and the compiler's complaint about it is not readable.
     *
     * Running a barrel project as an app fails deep inside the build with a message about a
     * missing entry class; saying so here is the difference between a sentence and an hour.
     */
    private fun checkKindSuitsProject(model: MonkeyCProject, root: Path, kind: MonkeyCRunKind) {
        val isBarrel = model.manifest(root)?.isBarrel ?: return
        if (isBarrel && !kind.barrel) {
            throw ExecutionException(
                "${root.name} is a barrel, not an app: it has no entry class and nothing to run. " +
                    "Use a Connect IQ Barrel configuration to build it, or Connect IQ Barrel Tests " +
                    "to run its tests.",
            )
        }
        if (!isBarrel && kind.barrel) {
            throw ExecutionException(
                "${root.name} is an app, not a barrel. Its manifest declares an application, " +
                    "so there is no <iq:barrel> to build.",
            )
        }
    }

    private fun title(options: MonkeyCRunOptions, device: String): String = when (options.kind) {
        MonkeyCRunKind.EXPORT -> "Exporting for every declared device"
        MonkeyCRunKind.BARREL -> "Building the barrel"
        MonkeyCRunKind.BARREL_TESTS -> "Building barrel tests for $device"
        MonkeyCRunKind.TESTS -> "Building tests for $device"
        MonkeyCRunKind.BUILD, MonkeyCRunKind.APP ->
            if (options.forDevice) "Building for $device, for the watch" else "Building for $device"
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
