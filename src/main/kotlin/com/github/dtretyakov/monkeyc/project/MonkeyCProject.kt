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

    fun manifest(root: Path): ManifestFile? = ManifestFile.parse(root.resolve(ManifestFile.FILE_NAME))

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
        val declared = manifest(root)?.devices.orEmpty()
        if (declared.isEmpty()) {
            // A barrel declares no products; it builds for anything new enough.
            val minimum = manifest(root)?.minSdkVersion
            return installed.filter { device ->
                minimum == null || device.sdkVersion == null || device.sdkVersion >= minimum
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
        return ConnectIqSdkService.getInstance().sdk?.defaultDeveloperKey?.takeIf { it.exists() }
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

    companion object {
        fun getInstance(project: Project): MonkeyCProject = project.service()
    }
}

private fun VirtualFile.toNioPathOrNull(): Path? = runCatching { toNioPath() }.getOrNull()
