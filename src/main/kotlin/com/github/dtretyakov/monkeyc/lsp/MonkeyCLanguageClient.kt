package com.github.dtretyakov.monkeyc.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** The answer the server expects from `custom/save`. */
data class SaveWorkspaceResult(val savedFiles: List<String>, val error: Boolean)

class MonkeyCLanguageClient(project: Project) : LanguageClientImpl(project) {

    /**
     * Saves the project's unsaved files, because the server asked.
     *
     * The Monkey C server analyses a workspace by compiling it, and the compiler reads from disk,
     * not from the editor. So before a full build it asks the client to flush what is unsaved; a
     * client that ignores the request gets diagnostics for code the user has already changed.
     *
     * This is not part of LSP — it is Garmin's own `custom/save`, and the reply shape is theirs.
     */
    @JsonRequest("custom/save")
    fun save(root: String): CompletableFuture<SaveWorkspaceResult> {
        val result = CompletableFuture<SaveWorkspaceResult>()
        ApplicationManager.getApplication().invokeLater {
            result.complete(runCatching { saveUnder(root) }.getOrElse { SaveWorkspaceResult(emptyList(), true) })
        }
        return result
    }

    private fun saveUnder(root: String): SaveWorkspaceResult {
        val rootPath = runCatching { Path.of(MonkeyCFileUriSupport.repair(root).removePrefix("file://")) }
            .getOrNull()
            ?: return SaveWorkspaceResult(emptyList(), true)

        val documents = FileDocumentManager.getInstance()
        val unsaved = documents.unsavedDocuments
            .mapNotNull { document -> documents.getFile(document)?.let { document to it } }
            .filter { (_, file) -> file.isUnder(rootPath) }

        if (unsaved.isEmpty()) return SaveWorkspaceResult(emptyList(), false)

        WriteCommandAction.runWriteCommandAction(project) {
            unsaved.forEach { (document, _) -> documents.saveDocument(document) }
        }
        LocalFileSystem.getInstance().refreshFiles(unsaved.map { it.second })

        return SaveWorkspaceResult(unsaved.map { it.second.url }, false)
    }

    private fun VirtualFile.isUnder(root: Path): Boolean =
        runCatching { toNioPath().startsWith(root) }.getOrDefault(false)
}
