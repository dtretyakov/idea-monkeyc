package com.github.dtretyakov.monkeyc.project

import com.intellij.javaee.ExternalResourceManagerEx
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType

/**
 * Tells the IDE the two things about a Connect IQ project it cannot work out for itself.
 *
 * There is no build file to read, so a project opened as a folder is just a folder: `source/` and
 * `resources/` are ordinary directories, and `bin/` — the compiler's output, with generated Monkey
 * C in `gen/`, the intermediate representation in `mir/` and resource caches besides — is indexed
 * and searched like the code. Generated `.mcgen` files then turn up in Go to File and in
 * find-in-path, next to the ones the user wrote.
 *
 * And the manifest's namespace has no schema anywhere — Garmin ships one for resource files and
 * none for the manifest — so the XML inspection reports every manifest as using an unregistered
 * URI. There is nothing for the user to do about that, and inventing a schema here would only
 * disagree with the compiler, which validates the manifest itself through the language server.
 */
class MonkeyCProjectSetup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = MonkeyCSettings.getInstance(project)

        // The module model may only be read under a read action, and only changed under a write
        // one — so which directories to touch is worked out first, and touched second.
        val work = readAction {
            if (settings.rootsConfigured) {
                emptyList()
            } else {
                val index = ProjectFileIndex.getInstance(project)
                MonkeyCProject.getInstance(project).roots().mapNotNull { root ->
                    val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
                        ?: return@mapNotNull null
                    index.getModuleForFile(directory)?.let { module -> module to directory }
                }
            }
        }

        val roots = readAction { MonkeyCProject.getInstance(project).roots() }
        if (work.isEmpty() && roots.isEmpty()) return

        chooseDeviceIfUnset(project, settings)

        edtWriteAction {
            if (project.isDisposed) return@edtWriteAction
            ignoreManifestNamespace(project)
            if (work.isNotEmpty()) {
                work.forEach { (module, directory) -> configure(module, directory) }
                settings.rootsConfigured = true
            }
        }
    }

    /**
     * Picks a target device the first time, so that Run does something rather than asking.
     *
     * Only when nothing is set: a project that has been opened before keeps whatever was chosen,
     * including a choice the user made and then a device they later removed from the manifest —
     * that is a mismatch worth showing rather than silently correcting.
     */
    private fun chooseDeviceIfUnset(project: Project, settings: MonkeyCSettings) {
        if (settings.targetDevice.isNotEmpty()) return
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot() ?: return
        settings.targetDevice = model.defaultDevice(root) ?: return
    }

    /**
     * Stops the XML inspection asking for a schema that does not exist.
     *
     * Scoped to the project rather than added to the IDE's own settings: the noise is this
     * project's, and it goes away with it.
     */
    private fun ignoreManifestNamespace(project: Project) {
        val resources = ExternalResourceManagerEx.getInstanceEx()
        if (resources.isIgnoredResource(CONNECT_IQ_NAMESPACE)) return
        runCatching { resources.addIgnoredResources(listOf(CONNECT_IQ_NAMESPACE), project) }
            .onFailure { LOG.warn("Could not ignore the Connect IQ namespace", it) }
    }

    private fun configure(module: Module, root: VirtualFile) {
        // Only an untouched module: one that already has source roots has been set up by someone,
        // and their arrangement is not ours to second-guess.
        if (ModuleRootManager.getInstance(module).sourceRoots.isNotEmpty()) return

        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.firstOrNull { it.file == root } ?: return@updateModel
            mark(entry, root, ProjectLayout.SOURCE_DIRECTORY, JavaSourceRootType.SOURCE)
            mark(entry, root, ProjectLayout.RESOURCE_DIRECTORY, JavaResourceRootType.RESOURCE)
            excludeOutput(entry)
        }
    }

    /**
     * The root types are the Java ones, and that is not a mistake: they live in the platform's
     * `jps-model`, not in the Java plugin, and they are the vocabulary every IDE and every build
     * tool reads an `.iml` with. A Monkey C source folder is a source folder.
     */
    private fun mark(entry: ContentEntry, root: VirtualFile, name: String, type: JpsModuleSourceRootType<*>) {
        val directory = root.findChild(name)?.takeIf { it.isDirectory } ?: return
        if (entry.sourceFolders.any { it.file == directory }) return
        runCatching { entry.addSourceFolder(directory, type) }
            .onFailure { LOG.warn("Could not mark $name as a $type root", it) }
    }

    /**
     * A pattern rather than the directory, because on a fresh checkout there is no `bin/` yet — and
     * a rule that only applies to a directory that already exists would never apply at all.
     */
    private fun excludeOutput(entry: ContentEntry) {
        if (ProjectLayout.OUTPUT_DIRECTORY in entry.excludePatterns) return
        runCatching { entry.addExcludePattern(ProjectLayout.OUTPUT_DIRECTORY) }
            .onFailure { LOG.warn("Could not exclude ${ProjectLayout.OUTPUT_DIRECTORY}", it) }
    }

    private companion object {
        const val CONNECT_IQ_NAMESPACE = "http://www.garmin.com/xml/connectiq"
        val LOG = logger<MonkeyCProjectSetup>()
    }
}
