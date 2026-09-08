package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

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

    override fun act(sdk: ConnectIqSdk): Pair<String, NotificationType> = when {
        Simulator.running(sdk.dataRoot).isEmpty() -> "The Connect IQ simulator was not running." to NotificationType.INFORMATION
        Simulator.stop(sdk) -> "The Connect IQ simulator has been stopped." to NotificationType.INFORMATION
        else -> "The Connect IQ simulator did not stop." to NotificationType.WARNING
    }
}

class RestartSimulatorAction : SimulatorAction() {

    override val progressTitle: String = "Restarting the Connect IQ simulator"

    override fun act(sdk: ConnectIqSdk): Pair<String, NotificationType> =
        if (Simulator.restart(sdk)) {
            "The Connect IQ simulator has been restarted." to NotificationType.INFORMATION
        } else {
            "The Connect IQ simulator did not come back. Start it from the SDK's bin directory." to
                NotificationType.ERROR
        }
}
