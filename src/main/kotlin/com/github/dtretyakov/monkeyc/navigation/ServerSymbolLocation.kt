package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.lsp.MonkeyCFileUriSupport
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Asks the SDK's language server where a symbol is declared, going the long way round.
 *
 * The server resolves a call on an instance perfectly well — that is the same inference completion
 * runs on, and `dc.setColor` completes because of it. What it will not do is *say* where the
 * result lives: `DefinitionContext` and `TypeDefinitionContext` both hand their resolved symbol to
 * `WorkspaceContext.getDefinitionLocation`, which knows only the project's own files, so an API
 * member comes back as an empty list.
 *
 * The hierarchy requests skip that path and answer with a location inside `bin/api.mir`. So this
 * asks for a call hierarchy, and for a type hierarchy when the caret is on a type rather than a
 * member. Both are documented by a live test against the real server, which will go red if Garmin
 * ever teaches `definition` about the API and makes this detour unnecessary.
 */
object ServerSymbolLocation {

    /** A location in a file the IDE can open, as the server described it. */
    data class Target(val file: VirtualFile, val line: Int, val character: Int)

    private const val TIMEOUT_MILLIS = 2_000L

    fun of(file: PsiFile, offset: Int): Target? {
        val project = file.project
        if (project.isDisposed) return null

        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        if (offset !in 0..document.textLength) return null

        // Through the plugin's own URI support, which canonicalises symlinks: a document the
        // server cannot match to a compiled file gets no answers at all.
        val uri = file.virtualFile?.let { MonkeyCFileUriSupport.getFileUri(it) } ?: return null
        val identifier = TextDocumentIdentifier(uri.toString())
        val at = positionOf(document, offset)

        return ask(file) { service ->
            service.prepareCallHierarchy(CallHierarchyPrepareParams(identifier, at))
                .thenApply { items -> items?.firstOrNull()?.let { it.uri to it.selectionRange.start } }
        } ?: ask(file) { service ->
            service.prepareTypeHierarchy(TypeHierarchyPrepareParams(identifier, at))
                .thenApply { items -> items?.firstOrNull()?.let { it.uri to it.selectionRange.start } }
        }
    }

    private fun ask(
        file: PsiFile,
        request: (org.eclipse.lsp4j.services.TextDocumentService) -> CompletableFuture<Pair<String, Position>?>,
    ): Target? {
        val answer = LanguageServiceAccessor.getInstance(file.project)
            .getLanguageServers(file, null, null)
            .thenCompose { servers ->
                servers.firstOrNull()
                    ?.let { request(it.textDocumentService) }
                    ?: CompletableFuture.completedFuture(null)
            }
            .orTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        // The pattern LSP4IJ's own go-to-definition uses: block, but stay cancellable, so a user
        // who changes their mind is not held by a server that is still thinking.
        try {
            ProgressIndicatorUtils.awaitWithCheckCanceled(answer)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: CancellationException) {
            return null
        } catch (_: Exception) {
            return null
        }

        val (uri, position) = runCatching { answer.getNow(null) }.getOrNull() ?: return null
        return resolve(uri)?.let { Target(it, position.line, position.character) }
    }

    /**
     * The server writes `file:/abs/path` where the protocol wants `file:///abs/path`, which is the
     * malformation [MonkeyCFileUriSupport] already repairs everywhere else.
     */
    private fun resolve(uri: String): VirtualFile? =
        runCatching { MonkeyCFileUriSupport.findFileByUri(uri) }.getOrNull()

    private fun positionOf(document: Document, offset: Int): Position {
        val line = document.getLineNumber(offset)
        return Position(line, offset - document.getLineStartOffset(line))
    }
}
