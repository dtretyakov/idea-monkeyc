package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.build.BuildSpec
import com.github.dtretyakov.monkeyc.build.MonkeyCBuildSession
import com.github.dtretyakov.monkeyc.build.MonkeyCBuilder
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Builds the `.iq` file the Connect IQ Store takes.
 *
 * An export is a different animal from a run: it builds for every device the manifest declares,
 * release-signed, and takes minutes rather than seconds. It is an action rather than a run
 * configuration because there is nothing to run at the end of it.
 */
class ExportAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible =
            project != null && MonkeyCProject.getInstance(project).primaryRoot() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot() ?: return

        val chosen = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Export Connect IQ App"),
            project,
            null,
        ) ?: return

        val manifest = model.manifest(root)
        val name = ProjectName.of(root)
        val output = Path.of(chosen.path).resolve(if (manifest?.isBarrel == true) "$name.barrel" else "$name.iq")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Exporting $name", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    export(project, model, root, output, manifest?.isBarrel == true)
                }
            },
        )
    }

    private fun export(
        project: Project,
        model: MonkeyCProject,
        root: Path,
        output: Path,
        isBarrel: Boolean,
    ) {
        val result = MonkeyCBuildSession.run(
            project,
            BuildSpec(
                kind = if (isBarrel) BuildKind.BARREL else BuildKind.EXPORT,
                root = root,
                output = output,
                jungleFiles = model.jungleFiles(root),
                developerKey = model.developerKey(),
            ),
            title = "Exporting to ${output.fileName}",
        )

        val group = NotificationGroupManager.getInstance().getNotificationGroup("Monkey C")
        if (result.succeeded) {
            group.createNotification("Exported to $output", NotificationType.INFORMATION).notify(project)
        } else {
            group.createNotification(
                "Export failed",
                MonkeyCBuilder.describeFailure(result),
                NotificationType.ERROR,
            ).notify(project)
        }
    }
}
