package com.github.dtretyakov.monkeyc.library

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkListener
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.JungleBarrels
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.ui.MonkeyCConfigurable
import com.github.dtretyakov.monkeyc.ui.MonkeyCIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * What the SDK and the project's barrels contribute to External Libraries, and when to recompute it.
 *
 * `getAdditionalProjectLibraries` is asked often and under a read action, so the answer is cached.
 * It depends on two things and both announce themselves: the chosen SDK, over
 * [ConnectIqSdkService.TOPIC], and the project's jungles, over the VFS. Anything else is stale only
 * until one of those moves.
 */
@Service(Service.Level.PROJECT)
class ConnectIqLibraries(private val project: Project) {

    private val generation = AtomicLong()

    @Volatile
    private var cached: Pair<Long, List<ConnectIqLibrary>>? = null

    fun libraries(): List<SyntheticLibrary> {
        val now = generation.get()
        cached?.let { (at, libraries) -> if (at == now) return libraries }
        return compute().also { cached = now to it }
    }

    /**
     * The directories to watch so the platform notices an SDK being replaced underneath it.
     *
     * The SDK lives outside every content root, so without this nothing in the IDE has any reason
     * to look at it again.
     */
    fun rootsToWatch(): List<VirtualFile> = libraries().flatMap { it.sourceRoots }

    /**
     * Drops the cache and tells the platform to re-render and re-index.
     *
     * Deferred into a write action on purpose, and for two reasons at once.
     * `fireAdditionalLibraryChanged` asserts write access, so it cannot be called from the settings
     * dialog where the SDK is switched; and the other caller is a VFS listener, which runs inside
     * the platform's own write action and is the one place a VFS refresh must not happen. Doing the
     * work later satisfies both.
     */
    fun invalidate() {
        val before = cached?.second?.flatMap { it.sourceRoots }.orEmpty()
        generation.incrementAndGet()

        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) return@invokeLater

                // The SDK sits outside every content root, so nothing else in the IDE has a reason
                // to look at it; without this a freshly installed SDK has no VirtualFile at all.
                refreshSdkIntoVfs()

                val after = libraries().flatMap { it.sourceRoots }
                if (before == after) return@invokeLater

                WriteAction.run<RuntimeException> {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project,
                        SDK_NAME,
                        before,
                        after,
                        SDK_NAME,
                    )
                }
            },
            project.disposed,
        )
    }

    /** Asynchronous on purpose: this can run while the IDE is doing something else. */
    private fun refreshSdkIntoVfs() {
        val bin = ConnectIqSdkService.getInstance().sdk?.root?.resolve("bin") ?: return
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(bin)
    }

    private fun compute(): List<ConnectIqLibrary> {
        val libraries = mutableListOf<ConnectIqLibrary>()

        ConnectIqSdkService.getInstance().sdk?.let { sdk ->
            apiSurface(sdk)?.let { root ->
                libraries += ConnectIqLibrary(
                    id = "connect-iq-sdk",
                    name = SDK_NAME,
                    location = sdk.version?.toString(),
                    icon = MonkeyCIcons.CONNECT_IQ,
                    roots = listOf(root),
                    onNavigate = {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, MonkeyCConfigurable::class.java)
                    },
                    navigateText = "Connect IQ Settings",
                )
            }
        }

        barrels().forEach { (barrel, root) ->
            libraries += ConnectIqLibrary(
                id = "connect-iq-barrel:$barrel",
                name = barrel.nameWithoutExtension,
                location = "barrel",
                icon = MonkeyCIcons.MONKEY_C,
                roots = listOf(root),
                onNavigate = {},
                navigateText = "Barrel",
            )
        }

        return libraries
    }

    /**
     * `bin/api.mir` — the whole Toybox API as text.
     *
     * There is no Monkey C source for Toybox anywhere in the SDK; the 234 `.mc` files it ships are
     * samples and templates. What there is, is this: Garmin's own intermediate representation, in
     * which every module, class, function and constant appears with its documentation comment and
     * its full signature, and only the bodies are empty. It is what the SDK's own language server
     * reads, and it is the only thing in the SDK a developer can be sent to.
     */
    private fun apiSurface(sdk: ConnectIqSdk): VirtualFile? =
        LocalFileSystem.getInstance().findFileByNioFile(sdk.root.resolve("bin/api.mir"))

    /**
     * The project's barrels, each as something that can be opened.
     *
     * A `.barrel` is a zip carrying the barrel's unmodified source under `content/`, which the
     * documentation is explicit about — "they contain all of the unmodified source of your Barrel
     * project" — so mounting it through `JarFileSystem` gives real, navigable Monkey C rather than
     * an opaque artifact.
     */
    private fun barrels(): List<Pair<Path, VirtualFile>> {
        val jungles = MonkeyCProject.getInstance(project).roots()
            .flatMap { MonkeyCProject.getInstance(project).jungleFiles(it) }

        return JungleBarrels.declaredIn(jungles).mapNotNull { path ->
            val local = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@mapNotNull null
            val root = if (path.extension == "barrel") {
                JarFileSystem.getInstance().getJarRootForLocalFile(local)
            } else {
                local
            }
            root?.let { path to it }
        }
    }

    companion object {
        const val SDK_NAME = "Connect IQ SDK"

        fun getInstance(project: Project): ConnectIqLibraries = project.service()
    }
}

/**
 * Recomputes when the SDK is switched or a jungle or barrel changes.
 *
 * Both signals are application-wide - the SDK is chosen once for the machine, and the VFS has one
 * bus - so this listens once and tells every open project, rather than each project listening for
 * itself.
 *
 * Adding a barrel is two edits, the jungle and the manifest; the tree not catching up until the
 * next restart would read as the feature not working at all.
 */
class ConnectIqLibraryWatcher : BulkFileListener, ConnectIqSdkListener {

    override fun after(events: List<VFileEvent>) {
        if (events.none { it.path.endsWith(".jungle") || it.path.endsWith(".barrel") }) return
        invalidateAll()
    }

    override fun sdkChanged(sdk: ConnectIqSdk?) = invalidateAll()

    private fun invalidateAll() = ProjectManager.getInstance().openProjects
        .filterNot { it.isDisposed }
        .forEach { ConnectIqLibraries.getInstance(it).invalidate() }
}
