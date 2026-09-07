package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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

        write(project, manifest, dialog.selected())

        // The target device may no longer be one the project declares.
        val settings = MonkeyCSettings.getInstance(project)
        if (settings.targetDevice.isNotEmpty() && settings.targetDevice !in dialog.selected()) {
            settings.targetDevice = dialog.selected().firstOrNull().orEmpty()
        }
        project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
    }

    private fun write(project: Project, manifest: Path, devices: List<String>) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file)
        val updated = ManifestFile.withDevices(document?.text ?: manifest.readText(), devices)

        WriteCommandAction.runWriteCommandAction(project, "Edit Products", null, {
            if (document != null) {
                document.setText(updated)
                FileDocumentManager.getInstance().saveDocument(document)
            } else {
                runWriteAction { file.setBinaryContent(updated.toByteArray()) }
            }
        })
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
        setOKButtonText("Apply")
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
