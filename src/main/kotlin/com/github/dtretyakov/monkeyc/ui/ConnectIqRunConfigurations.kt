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
        ProgramRunnerUtil.executeConfiguration(ensure(project, kind), executor)
    }

    /**
     * The one configuration a Connect IQ project always has, selected so Run is never dead.
     *
     * Called when the project opens. Without it the toolbar had nothing until the user opened a
     * `.mc` file or found a Build menu item, and what a project shows first is as likely to be a
     * README — so the first thing a newcomer met was a greyed-out Run button and no way to guess
     * why. Android Studio's `app` exists from the moment a project is imported and Xcode's scheme
     * is part of the project file; this is the same idea.
     *
     * Only when there is none already, and the selection is only taken when nothing is selected:
     * a project that has been opened before keeps whatever the user arranged.
     */
    fun createDefault(project: Project) {
        val manager = RunManager.getInstance(project)
        val settings = ensure(project, MonkeyCRunKind.APP)
        if (manager.selectedConfiguration == null) manager.selectedConfiguration = settings
    }

    /**
     * The configuration to run, reusing one the user already has.
     *
     * Making a new one on every invocation would fill the run history with copies of the same
     * thing, and lose whatever the user had set on it.
     */
    fun ensure(
        project: Project,
        kind: MonkeyCRunKind,
    ): RunnerAndConfigurationSettings {
        val manager = RunManager.getInstance(project)

        // Matched on the kind alone now: where a build goes is the target beside the Run button
        // rather than a flag on the configuration, so one Build configuration serves both.
        manager.allSettings
            .firstOrNull { candidate ->
                if (candidate.isTemporary) return@firstOrNull false
                val configuration = candidate.configuration as? MonkeyCRunConfiguration ?: return@firstOrNull false
                configuration.options.kind == kind
            }
            ?.let { return it }

        // The registered type, not a fresh instance of it: a configuration built from a factory
        // the platform does not know is one it cannot find again when the project is reopened.
        val factory = ConfigurationTypeUtil.findConfigurationType(MonkeyCRunConfigurationType::class.java)
            .configurationFactories
            .first { it.name == kind.display }

        return manager.createConfiguration(kind.display, factory).also {
            val configuration = it.configuration as MonkeyCRunConfiguration
            configuration.options.kind = kind
            manager.addConfiguration(it)
        }
    }
}
