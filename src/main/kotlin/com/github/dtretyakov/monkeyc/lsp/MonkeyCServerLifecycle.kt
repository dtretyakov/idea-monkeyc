package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkListener
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.MonkeyCSettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
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
        // Subscribed unconditionally. This used to give up when the project had no manifest at
        // open time, and then nothing could bring the server back for the rest of the session —
        // not adding a manifest, not changing a setting, only restarting the IDE. Whether there is
        // anything to restart is a question for the moment of restarting, not for startup.
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
        // Asked here rather than at startup: a manifest can appear after the project is open, and
        // the answer then has to be the current one.
        if (runReadActionBlocking { MonkeyCProject.getInstance(project).roots() }.isEmpty()) return

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
