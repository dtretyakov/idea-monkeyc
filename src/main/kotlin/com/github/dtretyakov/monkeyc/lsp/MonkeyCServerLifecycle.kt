package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkListener
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.MonkeyCSettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.redhat.devtools.lsp4ij.LanguageServerManager

/**
 * Restarts the language server when what it was told at startup stops being true.
 *
 * The server reads its settings once, at `initialize`: the SDK it was launched from, the jungle
 * files, the target device, the type check level. There is a notification for some of that, but
 * not for the SDK — and a server started from an SDK the user has since switched away from would
 * answer with the wrong API. Restarting is cheap and always correct, so that is what happens.
 */
class MonkeyCServerLifecycle : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Content roots are part of the module model, which may only be read under a read action.
        if (readAction { MonkeyCProject.getInstance(project).roots() }.isEmpty()) return

        val connection = project.messageBus.connect()
        connection.subscribe(
            MonkeyCSettings.TOPIC,
            MonkeyCSettingsListener { changed -> if (changed === project) restart(project) },
        )
        ApplicationManager.getApplication().messageBus.connect(connection)
            .subscribe(ConnectIqSdkService.TOPIC, ConnectIqSdkListener { restart(project) })
    }

    private fun restart(project: Project) {
        if (project.isDisposed) return
        val manager = LanguageServerManager.getInstance(project)
        manager.stop(
            MonkeyCLanguageServerFactory.SERVER_ID,
            // The user did not ask for the server to go away, only for it to catch up.
            LanguageServerManager.StopOptions().setWillDisable(false),
        )
        manager.start(
            MonkeyCLanguageServerFactory.SERVER_ID,
            LanguageServerManager.StartOptions().setForceStart(true),
        )
    }
}
