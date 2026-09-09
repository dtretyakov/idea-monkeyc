package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.build.BuildSpec
import com.github.dtretyakov.monkeyc.build.ExportPreflight
import com.github.dtretyakov.monkeyc.build.MonkeyCBuildSession
import com.github.dtretyakov.monkeyc.build.MonkeyCBuilder
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.DeviceProblems
import com.github.dtretyakov.monkeyc.project.ExportIdentity
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.MonkeyCTarget
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.DeveloperKey
import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.runReadAction
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
        onProgress: (String) -> Unit,
    ): BuiltArtifact {
        val sdk = ConnectIqSdkService.getInstance().sdkFor(project)
            ?: throw ExecutionException(
                "No Connect IQ SDK found. Install one with Garmin's SDK Manager, " +
                    "or set its location in Settings | Languages & Frameworks | Monkey C.",
            )

        val model = MonkeyCProject.getInstance(project)
        // A read action: this runs on a pooled thread, and the content roots behind primaryRoot()
        // may not be read without one.
        val root = runReadAction { model.primaryRoot() }
            ?: throw ExecutionException("No Connect IQ project here: none of the content roots holds a manifest.xml.")

        checkKindSuitsProject(model, root, options.kind)
        // Run No Evil runs in the simulator and nowhere else, so a watch target is a question with
        // no answer rather than a build to attempt.
        if (options.kind.isTests && onWatch(project, options)) {
            throw ExecutionException(
                "Unit tests run in the Connect IQ simulator, and the target is a watch. " +
                    "Choose the device under Simulator beside the Run button to run them.",
            )
        }

        val kind = options.kind
        val device = if (kind.buildKind.needsDevice) resolveDevice(project, model, root, options) else ""
        // Checked here rather than left to the compiler. All three ways of being wrong about a
        // device reach it as one complaint that names the device and not the reason, and the
        // commonest of them — declared in the manifest, never downloaded — is one the plugin can
        // see coming and say plainly.
        model.deviceProblem(root, device)?.let { throw ExecutionException(it) }
        // A barrel is unsigned, so it is the one kind that can be built without a key at all.
        val key = model.developerKey()
        if (key == null && kind.buildKind.needsDeveloperKey) {
            throw ExecutionException(model.developerKeyProblem())
        }
        // Only an export: a `.prg` for the simulator or a watch is signed with whatever key is to
        // hand and nothing downstream cares. An `.iq` is the artifact the store checks the key of,
        // and the check has no second chance.
        if (kind.buildKind == BuildKind.EXPORT) {
            if (key != null) checkSigningKey(project, key, onProgress)
            checkApplicationId(project, model, root, onProgress)
            preflight(model, root, onProgress)
        }

        val onWatch = onWatch(project, options)
        val simulator = !onWatch
        val output = outputFor(options, root, device, onWatch)

        val title = title(options, device, onWatch)
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

        val sdk = ConnectIqSdkService.getInstance().sdkFor(project)
            ?: throw ExecutionException("No Connect IQ SDK found.")
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
        onProgress: (String) -> Unit,
    ): PreparedLaunch {
        // Before the build, not after it: compiling for a minute and then refusing to run the
        // result would be the worst of both.
        if (onWatch(project, options)) {
            throw ExecutionException(
                "The target is a watch, so there is nothing to start in the simulator. " +
                    "Choose the same device under Simulator beside the Run button to run it there.",
            )
        }

        val built = build(project, options, onProgress)
        val paired = buildPaired(project, options, built.device, onProgress)

        // A simulator from another SDK holds the same ports, and `isReady` cannot tell them
        // apart — so without this the run would push a `.prg` built by one SDK into the other's
        // simulator, which fails in ways that read as a broken plugin. Happens whenever the SDK
        // Manager installs a new SDK while yesterday's simulator is still open.
        Simulator.conflictingSdk(built.sdk)?.let { other ->
            throw ExecutionException(
                "A Connect IQ simulator from ${other.fileName} is running and holding the port, " +
                    "but this project builds with ${built.sdk.root.fileName}. " +
                    "Restart it from Run | Connect IQ Simulator | Restart Simulator.",
            )
        }

        // The port answering is not the same as the simulator answering, and the difference is
        // invisible from here on: whatever holds it will not speak the debug shell's protocol, so
        // the failure surfaces as the app refusing to launch. Said now, while it is still cheap.
        if (Simulator.strangerHoldsPort(built.sdk)) {
            onProgress(
                "Something is listening on the simulator's port range (1234-1238) and it is not a " +
                    "Connect IQ simulator. If the run fails to launch, that is where to look.",
            )
        }

        onProgress("Starting the Connect IQ simulator...")
        if (!Simulator.start(built.sdk)) {
            throw ExecutionException(
                "The Connect IQ simulator did not start listening on 1234-1238. " +
                    "Either it failed to start — try it by hand from the SDK's bin directory — or " +
                    "something else holds those ports.",
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
    private fun outputFor(options: MonkeyCRunOptions, root: Path, device: String, onWatch: Boolean): Path {
        val chosen = options.outputPath.trim().takeIf { it.isNotEmpty() }?.let {
            val path = Path.of(it)
            if (path.isAbsolute) path else root.resolve(path)
        }
        return when (options.kind) {
            MonkeyCRunKind.EXPORT -> chosen ?: ProjectLayout.exportIq(root, root.name)
            MonkeyCRunKind.BARREL -> chosen ?: ProjectLayout.barrel(root, root.name)
            MonkeyCRunKind.TESTS, MonkeyCRunKind.BARREL_TESTS -> ProjectLayout.testPrg(root, root.name, device)
            // A watch build and a simulator build are different binaries that will not run in each
            // other's place, so they must not share a name — and the device in the name is what
            // says which watch the file on the desk belongs to.
            MonkeyCRunKind.APP, MonkeyCRunKind.BUILD ->
                if (onWatch) ProjectLayout.devicePrg(root, root.name, device) else ProjectLayout.appPrg(root, root.name)
        }
    }

    /**
     * A barrel is not an app, and the compiler's complaint about it is not readable.
     *
     * Running a barrel project as an app fails deep inside the build with a message about a
     * missing entry class; saying so here is the difference between a sentence and an hour.
     */
    private fun checkKindSuitsProject(model: MonkeyCProject, root: Path, kind: MonkeyCRunKind) {
        val isBarrel = model.manifest(root)?.isBarrel
            ?: throw ExecutionException(model.manifestProblem(root))
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

    private fun title(options: MonkeyCRunOptions, device: String, onWatch: Boolean): String = when (options.kind) {
        MonkeyCRunKind.EXPORT -> "Exporting for every declared device"
        MonkeyCRunKind.BARREL -> "Building the barrel"
        MonkeyCRunKind.BARREL_TESTS -> "Building barrel tests for $device"
        MonkeyCRunKind.TESTS -> "Building tests for $device"
        MonkeyCRunKind.BUILD, MonkeyCRunKind.APP ->
            if (onWatch) "Building for $device, for the watch" else "Building for $device"
    }

    /**
     * A Connect IQ executable is built for one device, so there is no such thing as running without
     * choosing one.
     *
     * The configuration's own choice wins, because a configuration that names a device was written
     * to mean it. Otherwise it is the one chosen next to the Run button, and failing that the
     * first device the manifest declares — asking would only be a question with one sensible
     * answer, and it is recorded so the selector shows what was built.
     */
    private fun resolveDevice(
        project: Project,
        model: MonkeyCProject,
        root: Path,
        options: MonkeyCRunOptions,
    ): String {
        resolveTarget(project, options)?.let { return it.device }

        // Nothing chosen anywhere — usually a project opened before the SDK had finished loading,
        // so the choice made at open time found no devices to make. Pick one now and record it, so
        // that the selector beside the Run button shows what is actually being built.
        val chosen = model.defaultDevice(root) ?: throw ExecutionException(noDeviceReason(model, root))
        MonkeyCSettings.getInstance(project).targetDevice = chosen
        return chosen
    }

    /**
     * The target this run will use: the configuration's pin, else the toolbar's choice.
     *
     * Needs no project root and no manifest, deliberately. An earlier version took both and fell
     * back to the old flag when it could not find them, which meant the destination silently
     * reverted outside a Connect IQ project — a fallback that could only ever be wrong.
     */
    fun resolveTarget(project: Project, options: MonkeyCRunOptions): MonkeyCTarget? = MonkeyCTarget.resolve(
        pinned = MonkeyCTarget.ofOptions(options.device, options.forDevice),
        chosen = MonkeyCSettings.getInstance(project).target,
        default = null,
    )

    /**
     * Whether this run goes to a watch rather than the simulator.
     *
     * Asked in two places that cannot share the build's own resolution — the process handler
     * decides whether to launch anything before the build has run — so the answer is computed the
     * same way from the same two sources rather than read off a flag that only one of them keeps
     * up to date.
     */
    fun onWatch(project: Project, options: MonkeyCRunOptions): Boolean =
        resolveTarget(project, options)?.onWatch ?: false

    /**
     * Why there is no device to build for, naming the devices rather than counting them.
     *
     * "Get them with the SDK Manager" is a different errand for one device than for nine, and
     * knowing which ones is what makes it an errand at all.
     */
    /**
     * Says what the export is about to leave out, before it spends minutes doing it.
     *
     * Errors stop it: a device that cannot be built or a trial the store will refuse means the
     * package is wrong, and finding that out after the upload costs days. Warnings do not, because
     * shipping to fewer devices than you thought is often a decision somebody already made — it
     * only has to be a decision they can see.
     */
    private fun preflight(model: MonkeyCProject, root: Path, onProgress: (String) -> Unit) {
        val manifest = model.manifest(root) ?: return
        val findings = ExportPreflight.check(manifest, ConnectIqSdkService.getInstance().devices())
        if (findings.isEmpty()) return

        findings.forEach { onProgress(it.text) }
        findings.firstOrNull { it.severity == ExportPreflight.Severity.ERROR }
            ?.let { throw ExecutionException(it.text) }
    }

    /**
     * Stops an export that would be signed with a key other than the one this project shipped with.
     *
     * Thrown rather than warned, because the mistake is unrecoverable and silent: the store rejects
     * the upload, and by then the only way forward is a new listing. Recording the first one costs
     * nothing and is what makes every later export checkable.
     */
    private fun checkSigningKey(project: Project, key: Path, onProgress: (String) -> Unit) {
        val settings = MonkeyCSettings.getInstance(project)
        val fingerprint = DeveloperKey.fingerprint(key)

        when (val verdict = ExportIdentity.check(settings.exportedWithKey, fingerprint)) {
            is ExportIdentity.Verdict.Changed -> throw ExecutionException(ExportIdentity.describeKey(verdict))

            is ExportIdentity.Verdict.FirstExport -> {
                settings.exportedWithKey = verdict.value
                onProgress("Signing with developer key ${verdict.value}, recorded for this project.")
            }

            ExportIdentity.Verdict.Fine -> fingerprint?.let { onProgress("Signing with developer key $it.") }
        }
    }

    /**
     * Stops an export that would go out under a different application id than the last one.
     *
     * The same shape as the key check and for the same reason: the mistake is silent, the package
     * it produces is valid, and the damage — a second listing, or an update to the wrong app —
     * happens at the store rather than here.
     */
    private fun checkApplicationId(
        project: Project,
        model: MonkeyCProject,
        root: Path,
        onProgress: (String) -> Unit,
    ) {
        val settings = MonkeyCSettings.getInstance(project)
        val applicationId = model.manifest(root)?.applicationId

        when (val verdict = ExportIdentity.check(settings.exportedAsApplicationId, applicationId)) {
            is ExportIdentity.Verdict.Changed ->
                throw ExecutionException(ExportIdentity.describeApplicationId(verdict))

            is ExportIdentity.Verdict.FirstExport -> {
                settings.exportedAsApplicationId = verdict.value
                onProgress("Exporting as application id ${verdict.value}, recorded for this project.")
            }

            ExportIdentity.Verdict.Fine -> Unit
        }
    }

    private fun noDeviceReason(model: MonkeyCProject, root: Path): String = DeviceProblems.noneAvailable(
        declared = model.manifest(root)?.devices.orEmpty(),
        undownloaded = model.undownloadedDevices(root),
        manifestName = model.manifestPath(root).fileName.toString(),
    )
}
