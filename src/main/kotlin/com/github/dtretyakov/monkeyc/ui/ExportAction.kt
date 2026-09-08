package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfiguration
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfigurationType
import com.github.dtretyakov.monkeyc.run.MonkeyCRunKind
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Builds the `.iq` file the Connect IQ Store takes, or the `.barrel` a library project produces.
 *
 * The work itself is a run configuration rather than something this action does: an export builds
 * for every device the manifest declares and takes minutes, and a run configuration is what gives
 * that a console, a progress bar, a Stop button and a place in the run history. The menu item
 * stays because a first export is not something anyone thinks to look for under Run.
 */
class ExportAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val root = project?.let { MonkeyCProject.getInstance(it).primaryRoot() }
        event.presentation.isEnabledAndVisible = root != null
        if (project != null && root != null) {
            val barrel = MonkeyCProject.getInstance(project).manifest(root)?.isBarrel == true
            event.presentation.text = if (barrel) "Build Connect IQ Barrel" else "Export Connect IQ App"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot() ?: return
        val kind = if (model.manifest(root)?.isBarrel == true) MonkeyCRunKind.BARREL else MonkeyCRunKind.EXPORT

        val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID) ?: return
        ProgramRunnerUtil.executeConfiguration(settings(project, kind), executor)
    }

    /**
     * The configuration to run, reusing one the user already has.
     *
     * Making a new temporary configuration on every invocation would fill the run history with
     * copies of the same thing, and lose any output directory the user had set on it.
     */
    private fun settings(project: Project, kind: MonkeyCRunKind): RunnerAndConfigurationSettings {
        val manager = RunManager.getInstance(project)
        manager.allSettings
            .firstOrNull { (it.configuration as? MonkeyCRunConfiguration)?.options?.kind == kind }
            ?.let { return it }

        val factory = MonkeyCRunConfigurationType().configurationFactories.first { it.name == kind.display }
        return manager.createConfiguration(kind.display, factory).also {
            (it.configuration as MonkeyCRunConfiguration).options.kind = kind
            manager.addConfiguration(it)
        }
    }
}
