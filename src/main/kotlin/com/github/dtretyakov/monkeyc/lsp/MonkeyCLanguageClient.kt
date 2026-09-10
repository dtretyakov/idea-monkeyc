package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The answer the server expects from `custom/save`. */
data class SaveWorkspaceResult(val savedFiles: List<String>, val error: Boolean)

class MonkeyCLanguageClient(project: Project) : LanguageClientImpl(project) {

    /** Completed when the server says its index is ready; nothing it answers before then is useful. */
    private val indexed = CompletableFuture<Unit>()
    private val indicatorShown = AtomicBoolean(false)

    /** One explanation per server. It repeats the failure for every file it is asked about. */
    private val failureExplained = AtomicBoolean(false)

    /**
     * Turns the server's quietest sentence into the one thing the user needs to see.
     *
     * The Monkey C server compiles the whole workspace before it can answer anything, and until it
     * has, completion returns nothing at all. It announces the end of that with
     * `Full workspace build successful` — over `window/logMessage`, which LSP4IJ files away in its
     * own console. So the first minute of every project looks exactly like a broken plugin: you
     * type `WatchUi.` and nothing happens, with no reason on screen.
     *
     * A background progress says what is going on, in the place the IDE already puts that news.
     */
    override fun logMessage(params: MessageParams) {
        super.logMessage(params)

        if (params.message.contains(INDEX_READY, ignoreCase = true)) {
            indexed.complete(Unit)
            return
        }
        explainFailure(params.message)
        if (indicatorShown.compareAndSet(false, true)) showIndexingProgress()
    }

    /**
     * Answers the question the server's own message declines to.
     *
     * Its worst failures are reported by printing an exception message that is `null`, and the
     * plugin can see what the server will not say — how many devices are downloaded, which of
     * their folders cannot be read. Once per server, because a server that cannot read the devices
     * says so about every file it is handed.
     */
    private fun explainFailure(message: String) {
        if (failureExplained.get()) return
        val cause = ServerFailures.explain(message, environment()) ?: return
        if (!failureExplained.compareAndSet(false, true)) return
        MonkeyCServerNotice.explain(project, cause)
    }

    private fun environment(): ServerFailures.Environment {
        val service = ConnectIqSdkService.getInstance()
        val paths = listOfNotNull(
            service.sdk?.root?.toString(),
            project.basePath,
        ).filter { ServerFailures.isAwkward(it) }
        return ServerFailures.Environment(
            devicesDownloaded = service.devices().size,
            unreadableDevices = service.unreadableDevices(),
            awkwardPaths = paths,
        )
    }

    private fun showIndexingProgress() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || indexed.isDone) return@invokeLater
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Monkey C: building the workspace index", true) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        // Bounded: a server that dies before announcing readiness must not leave a
                        // progress bar turning for the rest of the session.
                        runCatching { indexed.get(INDEX_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
                    }
                },
            )
        }
    }

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
        // `ModalityState.any()`, because the server is waiting on this future and a dialog must not
        // be what keeps it waiting. Under the default modality the runnable is held back for as
        // long as any modal window is open — the settings page, a rename, a commit dialog — and the
        // build behind this request stalls for exactly that long, having asked a question nobody
        // answers. And `project.disposed`, so a project closed while the server was mid-request
        // cancels the runnable rather than running a WriteCommandAction against a dead project.
        ApplicationManager.getApplication().invokeLater(
            {
                result.complete(runCatching { saveUnder(root) }.getOrElse { SaveWorkspaceResult(emptyList(), true) })
            },
            ModalityState.any(),
            project.disposed,
        )
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

    private companion object {
        /** The server's own words for "my index is ready". */
        const val INDEX_READY = "build successful"

        const val INDEX_TIMEOUT_MINUTES = 10L
    }
}
