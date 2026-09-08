package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfiguration
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfigurationType
import com.github.dtretyakov.monkeyc.run.MonkeyCRunKind
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Starting a Connect IQ run configuration from a menu item.
 *
 * The menu is for the things nobody thinks to look for under Run — a first export, the whole test
 * suite — but the work still belongs to a run configuration, which is what gives it a console, a
 * Stop button that means something, and a place in the run history.
 */
object ConnectIqRunConfigurations {

    fun run(project: Project, kind: MonkeyCRunKind) {
        val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
        if (executor == null) {
            // Should not happen — Run is part of the platform — but a menu item that does nothing
            // and leaves no trace is the worst way to find out that it did.
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Monkey C")
                .createNotification(
                    "Could not start ${kind.display}",
                    "The IDE has no Run executor, so there is nothing to run the configuration with.",
                    NotificationType.ERROR,
                )
                .notify(project)
            return
        }
        ProgramRunnerUtil.executeConfiguration(settings(project, kind), executor)
    }

    /**
     * The configuration to run, reusing one the user already has.
     *
     * Making a new one on every invocation would fill the run history with copies of the same
     * thing, and lose whatever the user had set on it.
     */
    private fun settings(project: Project, kind: MonkeyCRunKind): RunnerAndConfigurationSettings {
        val manager = RunManager.getInstance(project)
        manager.allSettings
            .firstOrNull { !it.isTemporary && (it.configuration as? MonkeyCRunConfiguration)?.options?.kind == kind }
            ?.let { return it }

        // The registered type, not a fresh instance of it: a configuration built from a factory
        // the platform does not know is one it cannot find again when the project is reopened.
        val factory = ConfigurationTypeUtil.findConfigurationType(MonkeyCRunConfigurationType::class.java)
            .configurationFactories
            .first { it.name == kind.display }

        return manager.createConfiguration(kind.display, factory).also {
            (it.configuration as MonkeyCRunConfiguration).options.kind = kind
            manager.addConfiguration(it)
        }
    }
}
