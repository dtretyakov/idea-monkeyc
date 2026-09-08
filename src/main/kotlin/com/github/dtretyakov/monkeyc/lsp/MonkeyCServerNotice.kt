package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.ui.MonkeyCConfigurable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager

/**
 * Tells the user the language server has gone, and offers the two things worth doing about it.
 *
 * Deliberately a balloon and not a banner: the editor keeps working — the lexer, the build, the
 * run and the debugger are all unaffected — so this is news, not an obstruction. What it must not
 * be is absent, which is what VS Code leaves behind after it quietly stops restarting.
 */
object MonkeyCServerNotice {

    fun announce(project: Project, verdict: MonkeyCServerHealth.Verdict) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            when (verdict) {
                MonkeyCServerHealth.Verdict.Crashed -> crashed(project)
                is MonkeyCServerHealth.Verdict.KeepsCrashing -> keepsCrashing(project, verdict.times)
            }
        }
    }

    private fun crashed(project: Project) = notify(
        project,
        "Monkey C code intelligence stopped",
        "The language server in the SDK exited on its own. Completion, diagnostics and navigation " +
            "are gone until it is started again; building, running and debugging are unaffected.",
        NotificationType.WARNING,
        restart(project),
    )

    private fun keepsCrashing(project: Project, times: Int) = notify(
        project,
        "Monkey C code intelligence keeps stopping",
        "The language server has exited $times times in the last few minutes, so starting it again " +
            "is unlikely to be the answer. It is a separate JVM that compiles the whole project: " +
            "an undownloaded device, a path it cannot read, or simply the size of the project can " +
            "all end it. Building, running and debugging are unaffected.",
        NotificationType.ERROR,
        restart(project),
        NotificationAction.createSimple("Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, MonkeyCConfigurable::class.java)
        },
        NotificationAction.createSimple("Turn off live analysis") {
            MonkeyCSettings.getInstance(project).liveAnalysis = false
            project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
        },
    )

    private fun restart(project: Project) = NotificationAction.createSimple("Start it again") {
        MonkeyCServerHealth.getInstance(project).forget()
        LanguageServerManager.getInstance(project).start(
            MonkeyCLanguageServerFactory.SERVER_ID,
            LanguageServerManager.StartOptions().setForceStart(true),
        )
    }

    private fun notify(
        project: Project,
        title: String,
        detail: String,
        type: NotificationType,
        vararg actions: NotificationAction,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Monkey C")
            .createNotification(title, detail, type)
            .apply { actions.forEach { addAction(it) } }
            .notify(project)
    }
}
