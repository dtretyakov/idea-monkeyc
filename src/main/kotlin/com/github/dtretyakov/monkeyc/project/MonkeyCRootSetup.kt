package com.github.dtretyakov.monkeyc.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tells the IDE which of a Connect IQ project's directories hold source and which hold output.
 *
 * A Connect IQ project has no build file for the IDE to read, so a project opened as a folder is
 * just a folder: nothing is a source root, and `bin/` — the compiler's output, with generated
 * Monkey C in `gen/`, the intermediate representation in `mir/` and resource caches besides — is
 * indexed and searched like the code. Generated `.mcgen` files then turn up in Go to File and in
 * find-in-path, next to the ones the user wrote.
 *
 * This runs once per project and then never again: a flag records that it has, so a developer who
 * rearranges the roots afterwards does not find them rearranged back on the next open.
 */
class MonkeyCRootSetup : ProjectActivity {

    override suspend fun execute(project: com.intellij.openapi.project.Project) {
        val settings = MonkeyCSettings.getInstance(project)
        if (settings.rootsConfigured) return

        val roots = MonkeyCProject.getInstance(project).roots()
        if (roots.isEmpty()) return

        val index = ProjectFileIndex.getInstance(project)
        val work = roots.mapNotNull { root ->
            val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: return@mapNotNull null
            index.getModuleForFile(directory)?.let { module -> module to directory }
        }
        if (work.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            runWriteAction { work.forEach { (module, directory) -> configure(module, directory) } }
            settings.rootsConfigured = true
        }
    }

    private fun configure(module: Module, root: VirtualFile) {
        // Only an untouched module: one that already has source roots has been set up by someone,
        // and their arrangement is not ours to second-guess.
        if (ModuleRootManager.getInstance(module).sourceRoots.isNotEmpty()) return

        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.firstOrNull { it.file == root } ?: return@updateModel
            markSource(entry, root, ProjectLayout.SOURCE_DIRECTORY)
            markExcluded(entry, root, ProjectLayout.OUTPUT_DIRECTORY)
        }
    }

    private fun markSource(entry: ContentEntry, root: VirtualFile, name: String) {
        val directory = root.findChild(name)?.takeIf { it.isDirectory } ?: return
        if (entry.sourceFolders.any { it.file == directory }) return
        runCatching { entry.addSourceFolder(directory, false) }
            .onFailure { LOG.warn("Could not mark $name as a source root", it) }
    }

    private fun markExcluded(entry: ContentEntry, root: VirtualFile, name: String) {
        val directory = root.findChild(name)?.takeIf { it.isDirectory } ?: return
        if (entry.excludeFolders.any { it.file == directory }) return
        runCatching { entry.addExcludeFolder(directory) }
            .onFailure { LOG.warn("Could not exclude $name", it) }
    }

    private companion object {
        val LOG = logger<MonkeyCRootSetup>()
    }
}
