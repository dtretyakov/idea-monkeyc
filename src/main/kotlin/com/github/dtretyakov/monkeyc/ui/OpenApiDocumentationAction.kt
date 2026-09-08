package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.lang.ApiMirFileType
import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.navigation.DottedChain
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.ApiDocumentation
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex
import com.github.dtretyakov.monkeyc.sdk.ApiMirService
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Opens Garmin's own write-up of the symbol under the caret.
 *
 * The SDK carries the complete reference as HTML, and hover already links into it — but only when
 * the server chose to put a link in the hover, and only from inside the popup. This makes it a
 * thing you can ask for: caret on `drawText`, and the page for `Toybox.Graphics.Dc` opens at the
 * right anchor. Offline, from the SDK on disk, so it is the documentation for the SDK actually
 * installed rather than for whatever is current on the web.
 */
class OpenApiDocumentationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isEnabledAndVisible =
            file != null && (file.fileType == MonkeyCFileType || file.fileType == ApiMirFileType)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return

        when (val outcome = resolve(editor, file)) {
            is Outcome.Page -> BrowserUtil.browse(outcome.url)
            is Outcome.Problem -> say(project, outcome.message)
        }
    }

    private sealed interface Outcome {
        class Page(val url: String) : Outcome
        class Problem(val message: String) : Outcome
    }

    /**
     * Every way this can come to nothing gets its own sentence.
     *
     * An action that silently does nothing is the failure this project keeps having to design
     * against: the user cannot tell "no SDK" from "no such symbol" from "this SDK ships no docs",
     * and only one of the three is worth doing anything about.
     */
    private fun resolve(editor: Editor, file: PsiFile): Outcome {
        val sdk = ConnectIqSdkService.getInstance().sdk
            ?: return Outcome.Problem("No Connect IQ SDK, so there is no documentation to open.")

        val chain = DottedChain.at(file.viewProvider.contents, editor.caretModel.offset)
            ?: return Outcome.Problem("Put the caret on a name first.")

        val index = ApiMirService.getInstance().index()
            ?: return Outcome.Problem("The SDK at ${sdk.root} has no bin/api.mir to look $chain up in.")

        val found: ApiMirIndex.Declaration = index.resolve(chain).firstOrNull()
            ?: return Outcome.Problem("$chain is not part of the Connect IQ API.")

        return ApiDocumentation.urlFor(sdk, found)
            ?.let { Outcome.Page(it) }
            ?: Outcome.Problem("The SDK at ${sdk.root} ships no page for ${found.qualifiedName}.")
    }

    private fun say(project: Project, message: String) = NotificationGroupManager.getInstance()
        .getNotificationGroup("Monkey C")
        .createNotification(message, NotificationType.INFORMATION)
        .notify(project)
}
