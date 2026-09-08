package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent

/**
 * Says so, in the editor, when the SDK cannot give this file what it needs.
 *
 * Without the notification the failure is invisible: a Monkey C file opens, nothing is underlined,
 * nothing completes, and there is no reason on screen to think the plugin is doing anything other
 * than its job.
 *
 * Shaped after the platform's own precedents for a toolchain that is not an `Sdk` —
 * `NodeDownloadEditorNotificationProvider` and `ShellcheckSetupNotificationProvider`. Both are
 * ordinary notification providers with two links, one of which fetches the missing thing, and a
 * close button whose state is remembered in `PropertiesComponent`. (`ProjectSdkSetupValidator`, the
 * other candidate, is for toolchains modelled as a real `Sdk`: its fix handler is built on
 * `SdkPopupFactory`, which has nothing to offer a directory of jars.)
 */
class MissingSdkNotification : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (PropertiesComponent.getInstance(project).getBoolean(DISMISSED)) return null
        if (!belongsToAConnectIqProject(project, file)) return null

        // The first thing actually in the way, in the order the checklist puts them: telling
        // someone their SDK has no language server is noise while they have no SDK at all.
        val problem = ConnectIqEnvironment.firstProblem(ConnectIqEnvironment.check(project))
            ?: return null

        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = "${problem.name}: ${problem.detail}"

                when (problem.fix) {
                    ConnectIqEnvironment.Fix.SDK_MANAGER -> createActionLabel(OpenSdkManager.label()) {
                        OpenSdkManager.invoke(project)
                    }

                    ConnectIqEnvironment.Fix.GENERATE_KEY, null -> Unit
                }

                createActionLabel("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MonkeyCConfigurable::class.java)
                    reconsider(project)
                }
                createActionLabel("Reload") {
                    ConnectIqSdkService.getInstance().refresh()
                    reconsider(project)
                }

                setCloseAction {
                    PropertiesComponent.getInstance(project).setValue(DISMISSED, true)
                    reconsider(project)
                }
            }
        }
    }

    /**
     * Any file inside a Connect IQ project, not only the three the plugin has a lexer for.
     *
     * A newcomer's first click is as likely to land on `manifest.xml` as on a `.mc` file, and until
     * now that click was answered with nothing at all.
     */
    private fun belongsToAConnectIqProject(project: Project, file: VirtualFile): Boolean =
        MonkeyCProject.getInstance(project).rootFor(file) != null

    private fun reconsider(project: Project) = EditorNotifications.getInstance(project).updateAllNotifications()

    private companion object {
        const val DISMISSED = "monkeyc.setupNotification.dismissed"
    }
}
