package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.lang.ApiMirFileType
import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.sdk.ApiMirService
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * F12 on a Toybox symbol, which has never gone anywhere.
 *
 * Garmin's language server indexes `api.mir` and can land Type and Call Hierarchy inside it, but
 * its `DefinitionContext` only ever searches the workspace's own files — there is no api.mir
 * branch in it at all. So `textDocument/definition` on `WatchUi.Menu2` comes back empty, in this
 * IDE and in VS Code alike, and the user gets nothing with no reason given.
 *
 * Registered last on purpose: this runs only when everything before it, LSP4IJ included, has
 * declined, which is precisely the case of a symbol that belongs to the SDK rather than to the
 * project.
 */
class ApiMirGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        if (file.fileType != MonkeyCFileType && file.fileType != ApiMirFileType) return null

        val index = ApiMirService.getInstance().index() ?: return null

        val found = ToyboxReference.resolve(index, file.viewProvider.contents, offset)
        if (found.isEmpty()) return null

        val apiMir = ApiMirService.getInstance().file() ?: return null
        val psi = PsiManager.getInstance(file.project).findFile(apiMir) ?: return null

        // The API file has a flat PSI of its own, so the leaf at the offset is the declared name -
        // a real element to navigate to, with no synthetic PSI needed to stand in for it.
        return found
            .mapNotNull { psi.findElementAt(it.offset) }
            .toTypedArray()
            .takeIf { it.isNotEmpty() }
    }
}
