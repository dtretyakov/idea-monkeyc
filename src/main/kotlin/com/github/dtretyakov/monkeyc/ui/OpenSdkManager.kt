package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.SdkManagerApp
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * The one gesture that unblocks a first run: get to Garmin's SDK Manager.
 *
 * It is the same button whether the manager is installed or not — installed, it opens; missing, it
 * goes to the page that offers it for this platform. The caller does not have to know which, and
 * neither does the user, which is the point: every message that used to end in "get them with the
 * SDK Manager" can now end in a button that does.
 */
object OpenSdkManager {

    /** Someone else's error text, ended so a sentence of ours can follow it. */
    private fun finished(message: String?): String {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return "It did not start."
        return if (text.last() in ".!?") text else "$text."
    }

    /** What to write on the button, which depends on whether there is anything to open. */
    fun label(): String = if (SdkManagerApp.isInstalled()) "Open SDK Manager" else "Get the SDK Manager"

    fun invoke(project: Project?) {
        val command = SdkManagerApp.startCommand()
        if (command == null) {
            BrowserUtil.browse(SdkManagerApp.DOWNLOAD_URL)
            return
        }

        runCatching { GeneralCommandLine(command).createProcess() }
            .onFailure {
                // Recorded but unstartable — moved, or a broken install. The page is still useful.
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Monkey C")
                    .createNotification(
                        "Could not start the Connect IQ SDK Manager",
                        // Finished, because the message is not ours: a ProcessNotCreatedException
                        // ends with `error=2, No such file or directory` and no full stop, and the
                        // sentence after it then ran straight on from the operating system's.
                        "${finished(it.message)} Opening Garmin's download page instead.",
                        NotificationType.WARNING,
                    )
                    .notify(project)
                BrowserUtil.browse(SdkManagerApp.DOWNLOAD_URL)
            }
    }
}

/**
 * The same gesture as a menu item, so it can be found rather than only stumbled upon.
 *
 * [OpenSdkManager] was reachable from the settings page, the missing-SDK banner and the new project
 * wizard, and from nowhere else — which meant Search Everywhere, the way most people look for a
 * command they know exists, could not find it at all. It is the one action that unblocks a machine
 * with no SDK on it, and that is a poor thing to hide.
 *
 * The text is not fixed, because the button is not: installed, it opens the manager; missing, it
 * goes to the page that offers it.
 */
class OpenSdkManagerAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.text = OpenSdkManager.label()
    }

    override fun actionPerformed(event: AnActionEvent) = OpenSdkManager.invoke(event.project)
}
