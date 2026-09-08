package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfiguration
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfigurationType
import com.github.dtretyakov.monkeyc.run.MonkeyCRunKind
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project

/**
 * Starting a Connect IQ run configuration from a menu item.
 *
 * The menu is for the things nobody thinks to look for under Run — a first export, the whole test
 * suite — but the work still belongs to a run configuration, which is what gives it a console, a
 * Stop button that means something, and a place in the run history.
 */
object ConnectIqRunConfigurations {

    fun run(project: Project, kind: MonkeyCRunKind, configure: (MonkeyCRunConfiguration) -> Unit = {}) {
        val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID) ?: return
        ProgramRunnerUtil.executeConfiguration(settings(project, kind, configure), executor)
    }

    /**
     * The configuration to run, reusing one the user already has.
     *
     * Making a new one on every invocation would fill the run history with copies of the same
     * thing, and lose whatever the user had set on it.
     */
    private fun settings(
        project: Project,
        kind: MonkeyCRunKind,
        configure: (MonkeyCRunConfiguration) -> Unit,
    ): RunnerAndConfigurationSettings {
        val manager = RunManager.getInstance(project)
        manager.allSettings
            .firstOrNull { (it.configuration as? MonkeyCRunConfiguration)?.options?.kind == kind }
            ?.let { return it }

        val factory = MonkeyCRunConfigurationType().configurationFactories.first { it.name == kind.display }
        return manager.createConfiguration(kind.display, factory).also {
            val configuration = it.configuration as MonkeyCRunConfiguration
            configuration.options.kind = kind
            configure(configuration)
            manager.addConfiguration(it)
        }
    }
}
