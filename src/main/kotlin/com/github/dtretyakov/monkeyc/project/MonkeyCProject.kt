package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * A Connect IQ project inside the IDE: its root, its manifest, its jungles and the devices it can
 * actually be built for.
 *
 * "Root" is the directory holding `manifest.xml`. Usually that is the project directory, but a
 * repository can hold an app and the barrels it uses side by side, so a file's root is found by
 * walking up from the file itself.
 */
@Service(Service.Level.PROJECT)
class MonkeyCProject(private val project: Project) {

    /** Every Connect IQ project in this IDE project, outermost first. */
    fun roots(): List<Path> {
        val roots = ProjectRootManager.getInstance(project).contentRoots
            .mapNotNull { it.toNioPathOrNull() }
            .filter { ProjectLayout.isProjectRoot(it) }
            .distinct()
        if (roots.isNotEmpty()) return roots
        return listOfNotNull(project.guessProjectDir()?.toNioPathOrNull()?.takeIf { ProjectLayout.isProjectRoot(it) })
    }

    /** The project a file belongs to, or null when it is not inside one. */
    fun rootFor(file: VirtualFile): Path? = file.toNioPathOrNull()?.let { rootFor(it) }

    fun rootFor(path: Path): Path? {
        var current: Path? = if (ProjectLayout.isProjectRoot(path)) path else path.parent
        val stop = project.guessProjectDir()?.toNioPathOrNull()?.parent
        while (current != null && current != stop) {
            if (ProjectLayout.isProjectRoot(current)) return current
            current = current.parent
        }
        return null
    }

    /** The single root when there is one, so commands that need "the project" have an answer. */
    fun primaryRoot(): Path? = roots().firstOrNull()

    fun manifest(root: Path): ManifestFile? = ManifestFile.parse(manifestPath(root))

    /**
     * The manifest the build will actually read.
     *
     * A jungle names its own with `project.manifest`, and that is how a project builds more than
     * one variant from one source tree — a real one in the wild has `monkey.jungle` naming
     * `manifest.xml` and `monkey-api51.jungle` naming `manifest-api51.xml`, with different products
     * and different minimum API levels. Reading `manifest.xml` regardless, which is what this used
     * to do, makes every device-derived answer here about the wrong file: the device selector, the
     * memory budget's target, the app-type filter and the missing-device diagnostics.
     *
     * The first jungle that names one wins, matching the compiler, which takes the jungles in the
     * order it is given them. A jungle that names none — the common case — means the default.
     */
    fun manifestPath(root: Path): Path = ProjectLayout.manifestPath(root, jungleFiles(root))

    /**
     * Why the manifest cannot be read, in words, or null when it can.
     *
     * `ManifestFile.parse` answers null for a file that is absent and for one that is malformed,
     * and callers guard with `?: return` — so a broken manifest used to switch the checks off
     * rather than report itself, and the run then failed on the compiler's own unreadable
     * complaint, which is exactly what those checks exist to prevent.
     */
    fun manifestProblem(root: Path): String? {
        val path = manifestPath(root)
        if (!path.exists()) {
            // Named by a jungle and absent is a different mistake from simply not being a Connect
            // IQ project, and only one of the two is a typo the user can fix in a second.
            val named = path.fileName != Path.of(ManifestFile.FILE_NAME)
            return if (named) {
                "${path.fileName} is named by a jungle file but does not exist."
            } else {
                "${path.fileName} is missing, so this is not a Connect IQ project."
            }
        }
        if (manifest(root) != null) return null
        return "${path.fileName} could not be read. Fix it in the editor: until then the plugin " +
            "cannot tell an app from a barrel, or find the devices to build for."
    }

    fun jungleFiles(root: Path): List<Path> =
        ProjectLayout.jungleFiles(root, MonkeyCSettings.getInstance(project).jungleFiles)

    fun artifactName(root: Path): String = ProjectLayout.artifactName(root.name)

    /**
     * Devices this project can be built for: the ones it declares, kept to the ones the SDK Manager
     * has downloaded. A device the manifest names but the machine lacks is not buildable, and one
     * the machine has but the manifest omits is not wanted.
     */
    fun buildableDevices(root: Path): List<ConnectIqDevice> {
        val installed = ConnectIqSdkService.getInstance().devices()
        val manifest = manifest(root)
        val declared = manifest?.devices.orEmpty()
        if (declared.isEmpty()) {
            // A barrel declares no products; it builds for anything new enough.
            val minimum = manifest?.minSdkVersion
            return installed.filter { device ->
                (minimum == null || device.sdkVersion == null || device.sdkVersion >= minimum) &&
                    device.supports(manifest?.appType)
            }
        }
        // In the manifest's order, not the catalogue's: the first product a developer lists is
        // the one they work against, and it is what the target device defaults to.
        return declared.mapNotNull { id -> installed.firstOrNull { it.id == id } }
    }

    /**
     * The device Build and Run use when the user has not picked one.
     *
     * Chosen once, when the project opens, rather than left empty: an empty selection reads as a
     * setting the user forgot, and every run fails with a question instead of doing something. The
     * platform's own target selector behaves the same way — it preselects the first ready target.
     */
    fun defaultDevice(root: Path): String? = buildableDevices(root).firstOrNull()?.id

    /**
     * The developer key to sign with: the project's, else the one the SDK Manager generated.
     *
     * The configured path is checked for existence rather than trusted. These settings live in
     * `.idea/monkeyc.xml` and are committed, so a path is as likely to have come from a teammate's
     * machine as from this one — and an unchecked path passes the "no key" gate and fails later,
     * inside the compiler, in words that are about signing rather than about a missing file.
     */
    fun developerKey(): Path? {
        val configured = MonkeyCSettings.getInstance(project).developerKeyPath.trim()
        if (configured.isNotEmpty()) return Path.of(configured).takeIf { it.exists() }
        return ConnectIqSdkService.getInstance().sdkFor(project)?.defaultDeveloperKey?.takeIf { it.exists() }
    }

    /**
     * Why there is no key to sign with, in words, or null when there is one.
     *
     * A configured path that is not there is a different problem from having chosen nothing, and
     * the two want different sentences: one is a file to find, the other is a key to make.
     */
    fun developerKeyProblem(): String? {
        if (developerKey() != null) return null

        val configured = MonkeyCSettings.getInstance(project).developerKeyPath.trim()
        return if (configured.isNotEmpty()) {
            "The developer key at $configured does not exist. The project's settings are committed, " +
                "so the path may have come from another machine. Choose or generate one in " +
                "Settings | Languages & Frameworks | Monkey C."
        } else {
            "No developer key. Set one in Settings | Languages & Frameworks | Monkey C, " +
                "or let the SDK Manager generate one."
        }
    }

    /**
     * Why this project cannot be built for [device], in words, or null when it can.
     *
     * Asked before the compiler is started. Without it the three ways of being wrong about a
     * device all arrive as the compiler's own complaint, which names the device and not the
     * reason — and the commonest of the three, a device the manifest declares that the SDK Manager
     * never downloaded, is one the plugin can see and offer to fix.
     */
    fun deviceProblem(root: Path, device: String): String? {
        val manifest = manifest(root)
        return DeviceProblems.of(
            device = device,
            declared = manifest?.devices.orEmpty(),
            installed = ConnectIqSdkService.getInstance().device(device),
            appType = manifest?.appType,
            manifestName = manifestPath(root).fileName.toString(),
        )
    }

    /**
     * Every device the manifest declares that the SDK Manager has not downloaded.
     *
     * Named rather than counted: "get them with the SDK Manager" is a different errand when it is
     * one device from when it is nine, and knowing which is what makes it doable.
     */
    fun undownloadedDevices(root: Path): List<String> {
        val installed = ConnectIqSdkService.getInstance().devices().map { it.id }.toSet()
        return manifest(root)?.devices.orEmpty().filter { it !in installed }
    }

    companion object {
        fun getInstance(project: Project): MonkeyCProject = project.service()
    }
}

private fun VirtualFile.toNioPathOrNull(): Path? = runCatching { toNioPath() }.getOrNull()
