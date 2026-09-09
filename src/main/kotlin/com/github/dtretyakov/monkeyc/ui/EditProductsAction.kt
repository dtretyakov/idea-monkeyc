package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.project.ManifestText
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
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
        // The manifest the build reads, which a jungle can name: writing products into
        // `manifest.xml` while the build uses `manifest-api51.xml` edits a file nobody compiles.
        val manifest = model.manifestPath(root)

        val installed = ConnectIqSdkService.getInstance().devices()
        val minimum = model.manifest(root)?.minSdkVersion
        val appType = model.manifest(root)?.appType
        val newEnough = installed.filter { minimum == null || it.sdkVersion == null || it.sdkVersion >= minimum }
        // A watch that only runs watch faces has no business in a data field's product list. Offering
        // it is the reported behaviour of Garmin's own wizard, and the manifest it writes then fails
        // to build for a device the developer was invited to tick.
        val offered = newEnough.filter { it.supports(appType) }
        val selected = model.manifest(root)?.devices.orEmpty().toSet()
        // Whatever the manifest already declares stays in the table, fit or not: Save writes the
        // ticks as the whole product list, so a device filtered out here is a device silently
        // deleted from the manifest by an edit that had nothing to do with it.
        val eligible = (offered + installed.filter { it.id in selected }).distinctBy { it.id }
        // And the ones no profile was ever downloaded for, which the table cannot build a row from
        // and would otherwise drop just as quietly.
        val undownloaded = selected.filterNot { id -> installed.any { it.id == id } }

        val dialog = ProductsDialog(
            project,
            eligible,
            selected,
            // Counted together: a table holding nothing but rows the manifest declares is still a
            // table, and replacing it with "no devices are downloaded" is how Save loses them.
            EmptyReason.of(
                installed.size,
                newEnough.size,
                eligible.size + undownloaded.size,
                minimum,
                appType,
            ),
            appType,
            undownloaded = undownloaded,
        )
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

/**
 * Why there is nothing to tick, when there is nothing to tick.
 *
 * Garmin's own VS Code extension has this bug on file: a user who had downloaded their watch found
 * "Edit Products" empty, because the app's minimum API level was above what that watch supports and
 * the device was filtered out silently. An empty list has to say which of the two things happened,
 * because the two have different remedies.
 */
internal sealed interface EmptyReason {
    object None : EmptyReason
    object NothingDownloaded : EmptyReason
    data class AllBelowMinimum(val installed: Int, val minimum: SdkVersion) : EmptyReason
    data class NoneRunsThisKind(val installed: Int, val appType: String) : EmptyReason

    companion object {
        /**
         * Asked in the order the filters run, so the reason given is the one that actually emptied
         * the list rather than whichever is checked first.
         */
        fun of(
            installed: Int,
            newEnough: Int,
            eligible: Int,
            minimum: SdkVersion?,
            appType: String?,
        ): EmptyReason = when {
            eligible > 0 -> None
            installed == 0 -> NothingDownloaded
            newEnough == 0 && minimum != null -> AllBelowMinimum(installed, minimum)
            newEnough == 0 -> NothingDownloaded
            appType != null -> NoneRunsThisKind(newEnough, appType)
            else -> NothingDownloaded
        }
    }
}

internal class ProductsDialog(
    private val project: Project,
    private val devices: List<ConnectIqDevice>,
    selected: Set<String>,
    private val empty: EmptyReason,
    private val appType: String?,
    undownloaded: List<String> = emptyList(),
) : DialogWrapper(project) {

    private val products = ProductTable(devices, selected, appType, undownloaded)
    private val summary = JBLabel()

    init {
        title = "Connect IQ Products"
        setOKButtonText("Save")
        products.onChanged = { refreshSummary() }
        init()
        refreshSummary()
    }

    /**
     * The line under the table, which is the only place any of this is said.
     *
     * The ticks answer "which watches"; this answers what they cost — how many resource families
     * have to be drawn and kept working, and the smallest memory budget the code now has to fit,
     * which is the one that actually constrains it.
     */
    private fun refreshSummary() {
        summary.text = DeviceSelectionSummary.of(
            products.selectedDevices(),
            appType,
            products.selected().size,
        ) ?: "Nothing selected"
    }

    override fun createCenterPanel(): JComponent = panel {
        when (empty) {
            EmptyReason.None -> {
                row {
                    cell(JBScrollPane(products.table)).resizableColumn().align(Align.FILL)
                }.resizableRow()
                row {
                    button("All") { products.setAll({ true }, value = true); refreshSummary() }
                    button("None") { products.setAll({ true }, value = false); refreshSummary() }
                }
                row { cell(summary) }
                row {
                    comment(
                        "Devices downloaded with the SDK Manager that support the manifest's " +
                            "minimum API level and can run this kind of app, plus whatever the " +
                            "manifest already declares. Sort by Memory to find the device the " +
                            "app has to fit inside.",
                    )
                }
            }

            EmptyReason.NothingDownloaded -> {
                row { label("No devices are downloaded.") }
                row {
                    comment("A Connect IQ app is built for a device, so there is nothing to choose from yet.")
                }
                row { link(OpenSdkManager.label()) { OpenSdkManager.invoke(project) } }
            }

            is EmptyReason.NoneRunsThisKind -> {
                row { label("None of the ${empty.installed} downloaded devices runs a ${empty.appType}.") }
                row {
                    comment(
                        "Every device declares which kinds of app it can run. Change the type in " +
                            "the Manifest tab, or download a device that runs this one.",
                    )
                }
                row { link(OpenSdkManager.label()) { OpenSdkManager.invoke(project) } }
            }

            is EmptyReason.AllBelowMinimum -> {
                row { label("None of the ${empty.installed} downloaded devices supports API level ${empty.minimum}.") }
                row {
                    comment(
                        "That is the minimum this manifest asks for. Lower it in the Manifest tab, " +
                            "or download a newer device.",
                    )
                }
                row { link(OpenSdkManager.label()) { OpenSdkManager.invoke(project) } }
            }
        }
    }.also { it.preferredSize = java.awt.Dimension(420, 480) }

    fun selected(): List<String> = products.selected()
}
