package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.lang.JungleFileType
import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.lang.MssFileType
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/**
 * Says so, in the editor, when the SDK cannot give this file what it needs.
 *
 * Without the notification the failure is invisible: a Monkey C file opens, nothing is underlined,
 * nothing completes, and there is no reason on screen to think the plugin is doing anything other
 * than its job.
 */
class MissingSdkNotification : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (file.fileType !in MONKEY_C_FILES) return null

        val sdk = ConnectIqSdkService.getInstance().sdk
        val message = when {
            sdk == null ->
                "No Connect IQ SDK found. Install one with Garmin's SDK Manager to get code " +
                    "intelligence, building and the simulator."

            !sdk.hasLanguageServer ->
                "The Connect IQ SDK at ${sdk.root} has no language server, so there is no code " +
                    "intelligence. That arrived in SDK ${SdkVersion.LANGUAGE_SERVER_MINIMUM}" +
                    (sdk.version?.let { "; this one is $it." } ?: ".")

            ConnectIqSdkService.getInstance().devices().isEmpty() ->
                "No devices are downloaded. Get some with the SDK Manager — a Connect IQ app is " +
                    "built for a device, so there is nothing to build for until then."

            else -> return null
        }

        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = message
                createActionLabel("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MonkeyCConfigurable::class.java)
                }
                createActionLabel("Reload SDK") {
                    ConnectIqSdkService.getInstance().refresh()
                }
            }
        }
    }

    private companion object {
        val MONKEY_C_FILES = setOf(MonkeyCFileType, JungleFileType, MssFileType)
    }
}
