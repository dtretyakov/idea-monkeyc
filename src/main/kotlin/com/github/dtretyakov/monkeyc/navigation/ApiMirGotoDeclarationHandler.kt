package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.lang.ApiMirFileType
import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.sdk.ApiMirService
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * F12 on a Toybox symbol, which has never gone anywhere.
 *
 * The SDK's language server resolves these perfectly well — it is the same inference that makes
 * completion work — but it will not say where the answer lives. `DefinitionContext` and
 * `TypeDefinitionContext` both hand their resolved symbol to `WorkspaceContext`, which knows only
 * the project's own files, so an API member comes back as an empty list. In VS Code too.
 *
 * Three ways to answer, in the order they are cheap:
 *
 *  1. The name as written, against the index of `api.mir` — `WatchUi.Menu2`, `Graphics.COLOR_WHITE`.
 *     Instant, and needs no server at all.
 *  2. The server, over a hierarchy request, which is the one path that does report a location
 *     inside `api.mir`. This is what answers `dc.setColor` and every other call on an instance,
 *     and it is the compiler's own answer rather than a guess.
 *  3. Failing both — no server yet, still indexing, no SDK — the type written beside the receiver
 *     in the source.
 *
 * Registered last, so all of this runs only after LSP4IJ has already declined, which is exactly
 * the case of a symbol belonging to the SDK rather than to the project.
 */
class ApiMirGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        if (file.fileType != MonkeyCFileType && file.fileType != ApiMirFileType) return null

        val index = ApiMirService.getInstance().index()

        index?.let { ToyboxReference.byName(it, file.viewProvider.contents, offset) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return inApiMir(file, it.map { found -> found.offset }) }

        fromServer(file, offset)?.let { return it }

        return index
            ?.let { ToyboxReference.byGuess(it, file.viewProvider.contents, offset) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { inApiMir(file, it.map { found -> found.offset }) }
    }

    /**
     * The server's answer, turned into something to navigate to.
     *
     * It arrives as a file and a line, which is all that is needed: the API file has a flat PSI of
     * its own, so the leaf at that offset is a real element and no synthetic one has to stand in.
     */
    private fun fromServer(file: PsiFile, offset: Int): Array<PsiElement>? {
        val target = ServerSymbolLocation.of(file, offset) ?: return null
        val psi = PsiManager.getInstance(file.project).findFile(target.file) ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(psi) ?: return null
        if (target.line !in 0 until document.lineCount) return null

        val at = document.getLineStartOffset(target.line) + target.character
        if (at > document.getLineEndOffset(target.line)) return null

        // A hierarchy request on a declaration answers with that declaration. Going to where the
        // caret already is reads as F12 doing nothing, so let something else try.
        if (psi == file && at == offset) return null

        return psi.findElementAt(at)?.let { arrayOf(it) }
    }

    private fun inApiMir(file: PsiFile, offsets: List<Int>): Array<PsiElement>? {
        val apiMir = ApiMirService.getInstance().file() ?: return null
        val psi = PsiManager.getInstance(file.project).findFile(apiMir) ?: return null

        return offsets
            .mapNotNull { psi.findElementAt(it) }
            .toTypedArray()
            .takeIf { it.isNotEmpty() }
    }
}
