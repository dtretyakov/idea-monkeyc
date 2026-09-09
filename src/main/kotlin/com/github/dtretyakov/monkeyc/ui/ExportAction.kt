package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.run.MonkeyCRunKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadAction

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
        // A read action because `update` runs on a background thread and the content roots are
        // project model state.
        val root = project?.let { runReadAction { MonkeyCProject.getInstance(it).primaryRoot() } }
        event.presentation.isEnabledAndVisible = root != null
        if (project != null && root != null) {
            val barrel = MonkeyCProject.getInstance(project).manifest(root)?.isBarrel == true
            event.presentation.text = if (barrel) "Build Connect IQ Barrel" else "Export Connect IQ App"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val model = MonkeyCProject.getInstance(project)
        val root = runReadAction { model.primaryRoot() } ?: return
        val kind = if (model.manifest(root)?.isBarrel == true) MonkeyCRunKind.BARREL else MonkeyCRunKind.EXPORT

        ConnectIqRunConfigurations.run(project, kind)
    }
}
