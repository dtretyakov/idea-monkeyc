package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.SdkManagerApp
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
                        "${it.message ?: "It did not start."} Opening Garmin's download page instead.",
                        NotificationType.WARNING,
                    )
                    .notify(project)
                BrowserUtil.browse(SdkManagerApp.DOWNLOAD_URL)
            }
    }
}
