package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.run.SimulatorProcess
import com.github.dtretyakov.monkeyc.run.SimulatorStorage
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile

/**
 * Closing and reopening the simulator by hand.
 *
 * The plugin starts the simulator when a run needs one and never closes it, which is right — it
 * holds the state a developer is looking at. But it does get stuck: an app that hangs, a device
 * left half-loaded, a port still held after a crash. Until now the only way out was Activity
 * Monitor.
 */
abstract class SimulatorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = ConnectIqSdkService.getInstance().sdk != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // The project's SDK: stopping and starting the simulator has to act on the one the runs
        // from this project use, which a pinned project can make a different one.
        val sdk = ConnectIqSdkService.getInstance().sdkFor(project) ?: return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, progressTitle, false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    report(project, act(sdk))
                }
            },
        )
    }

    protected abstract val progressTitle: String

    /** Does the work, and returns what to tell the user. */
    protected abstract fun act(sdk: ConnectIqSdk): Pair<String, NotificationType>

    private fun report(project: Project, outcome: Pair<String, NotificationType>) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Monkey C")
            .createNotification(outcome.first, outcome.second)
            .notify(project)
    }
}

class StopSimulatorAction : SimulatorAction() {

    override val progressTitle: String = "Stopping the Connect IQ simulator"

    override fun act(sdk: ConnectIqSdk): Pair<String, NotificationType> {
        // Asked before stopping, because stopping is what makes the answer false.
        val ours = SimulatorProcess.getInstance().owns(sdk)
        return when {
            Simulator.running(sdk.dataRoot).isEmpty() ->
                "The Connect IQ simulator was not running" to NotificationType.INFORMATION

            Simulator.stop(sdk) -> if (ours) {
                "The Connect IQ simulator has been stopped" to NotificationType.INFORMATION
            } else {
                // Worth distinguishing: this one was started outside the IDE — from the SDK
                // Manager, or by a previous session — and the developer may well want to know that
                // what just closed was not something this window opened.
                "The Connect IQ simulator has been stopped. It was started outside this IDE." to
                    NotificationType.INFORMATION
            }

            else -> "The Connect IQ simulator did not stop" to NotificationType.WARNING
        }
    }
}

class RestartSimulatorAction : SimulatorAction() {

    override val progressTitle: String = "Restarting the Connect IQ simulator"

    override fun act(sdk: ConnectIqSdk): Pair<String, NotificationType> =
        if (Simulator.restart(sdk)) {
            "The Connect IQ simulator has been restarted" to NotificationType.INFORMATION
        } else {
            "The Connect IQ simulator did not come back. Start it from the SDK's bin directory." to
                NotificationType.ERROR
        }
}

/**
 * Throws away what the simulator remembers about the apps it has run.
 *
 * The reason this earns a menu entry is a confusion nobody has written down properly. A value in
 * `properties.xml` is a *default*, used when the property does not yet exist — and once the app
 * has run once, it exists. So editing the default and running again shows the old value, the
 * developer concludes the edit did not take, and there is nothing on screen to suggest otherwise.
 *
 * The folklore remedy on the forums is to delete the simulator's whole temporary directory, which
 * also throws away the installed programs. This removes only the stored settings and the persisted
 * `Application.Storage`, which is what actually stands in the way.
 */
class ClearSimulatorDataAction : SimulatorAction() {

    override val progressTitle: String = "Clearing the Connect IQ simulator's stored data"

    override fun act(sdk: ConnectIqSdk): Pair<String, NotificationType> {
        if (SimulatorStorage.deviceRoot() == null) {
            return "The simulator has stored nothing yet on this machine" to NotificationType.INFORMATION
        }
        if (!SimulatorStorage.hasPersistedData()) {
            return "The simulator has no stored app data to clear" to NotificationType.INFORMATION
        }

        // Stopped first: the simulator holds this directory open, and clearing it underneath a
        // running app leaves the app writing into files that are no longer there.
        val wasRunning = Simulator.running(sdk.dataRoot).isNotEmpty()
        if (wasRunning) Simulator.stop(sdk)

        val removed = SimulatorStorage.clearPersistedData()
        return "Cleared $removed stored file${if (removed == 1) "" else "s"}. " +
            "The defaults in properties.xml apply again on the next run." to NotificationType.INFORMATION
    }
}

/**
 * Shows what the simulator has printed since it started.
 *
 * There is no other way to see it. The simulator narrates its ANT and BLE stack continuously, so
 * the plugin keeps the output rather than showing it — and this is the door to it for the times
 * when something is wrong in a way the run console cannot explain: a device that will not load, a
 * crash that leaves the window standing, a port that closes by itself.
 *
 * Only for a simulator this IDE started. One found running was launched somewhere with a console
 * of its own, and inventing an empty panel for it would suggest we had looked and found nothing.
 */
class ShowSimulatorLogAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val sdk = event.project?.let { ConnectIqSdkService.getInstance().sdkFor(it) }
        event.presentation.isEnabledAndVisible = sdk != null && SimulatorProcess.getInstance().owns(sdk)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val sdk = ConnectIqSdkService.getInstance().sdkFor(project) ?: return
        val log = SimulatorProcess.getInstance().log(sdk)

        if (log.isBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Monkey C")
                .createNotification(
                    "The Connect IQ simulator has printed nothing yet",
                    NotificationType.INFORMATION,
                )
                .notify(project)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            // A read-only editor rather than a message box: this is output, it is long, and it is
            // worth being able to scroll and copy out of.
            val file = LightVirtualFile("Connect IQ Simulator.log", PlainTextFileType.INSTANCE, log)
            file.isWritable = false
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}

