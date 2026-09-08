package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.project.ManifestText
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.readText

/**
 * Adds and removes the devices a project is built for.
 *
 * This is the one manifest edit that happens often — a new watch comes out, or an old one turns out
 * not to have the API the app needs — and doing it by hand means copying device ids nobody
 * remembers out of the SDK Manager.
 */
class EditProductsAction : AnAction() {

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
        val manifest = root.resolve(ManifestFile.FILE_NAME)

        val installed = ConnectIqSdkService.getInstance().devices()
        val minimum = model.manifest(root)?.minSdkVersion
        val eligible = installed.filter { minimum == null || it.sdkVersion == null || it.sdkVersion >= minimum }
        val selected = model.manifest(root)?.devices.orEmpty().toSet()

        val dialog = ProductsDialog(project, eligible.map { it.id to it.displayName }, selected)
        if (!dialog.showAndGet()) return

        // Nothing else happens unless the file actually changed. The settings below follow the
        // manifest, and moving them for a write that did not land is how the two drift apart.
        if (!write(project, manifest, dialog.selected())) return

        // The target device may no longer be one the project declares.
        val settings = MonkeyCSettings.getInstance(project)
        if (settings.targetDevice.isNotEmpty() && settings.targetDevice !in dialog.selected()) {
            settings.targetDevice = dialog.selected().firstOrNull().orEmpty()
        }
        project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
    }

    /**
     * Writes the chosen devices into the manifest, and says whether it managed to.
     *
     * It used to give up silently when the file could not be reached: the dialog closed, the
     * manifest was untouched, and the settings were rewritten anyway — leaving a project whose
     * target device is one the manifest does not declare, with nothing on screen about it.
     */
    private fun write(project: Project, manifest: Path, devices: List<String>): Boolean {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest)
        if (file == null) {
            report(project, "$manifest could not be read.")
            return false
        }

        val document = FileDocumentManager.getInstance().getDocument(file)
        val current = runCatching { document?.text ?: manifest.readText() }.getOrNull()
        if (current == null) {
            report(project, "${manifest.fileName} could not be read.")
            return false
        }

        val updated = ManifestText.withDevices(current, devices)

        return runCatching {
            WriteCommandAction.runWriteCommandAction(project, "Edit Products", null, {
                if (document != null) {
                    document.setText(updated)
                    FileDocumentManager.getInstance().saveDocument(document)
                } else {
                    runWriteAction { file.setBinaryContent(updated.toByteArray()) }
                }
            })
            true
        }.getOrElse {
            report(project, "${manifest.fileName} could not be written: ${it.message ?: "the write failed."}")
            false
        }
    }

    private fun report(project: Project, detail: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Monkey C")
            .createNotification("The products were not saved", detail, NotificationType.ERROR)
            .notify(project)
    }
}

private class ProductsDialog(
    project: Project,
    private val devices: List<Pair<String, String>>,
    selected: Set<String>,
) : DialogWrapper(project) {

    private val list = CheckBoxList<String>().apply {
        devices.forEach { (id, displayName) -> addItem(id, "$displayName  ($id)", id in selected) }
    }

    init {
        title = "Connect IQ Products"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(JBScrollPane(list)).resizableColumn()
        }.resizableRow()
        row {
            comment(
                "Only devices downloaded with the SDK Manager that support the manifest's " +
                    "minimum API level are listed.",
            )
        }
    }.also { it.preferredSize = java.awt.Dimension(420, 480) }

    fun selected(): List<String> = devices.map { it.first }.filter { list.isItemSelected(it) }
}
