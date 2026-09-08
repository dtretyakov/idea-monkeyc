package com.github.dtretyakov.monkeyc.ui.wizard

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.sdk.AppType
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.sdk.NewProject
import com.github.dtretyakov.monkeyc.sdk.ProjectGenerator
import com.github.dtretyakov.monkeyc.sdk.ProjectInfo
import com.github.dtretyakov.monkeyc.sdk.ProjectTemplate
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import com.github.dtretyakov.monkeyc.ui.OpenSdkManager
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import java.nio.file.Path

/**
 * What a new Connect IQ project needs beyond its name: what to start from, the oldest API level it
 * will run on, and which device to build for.
 *
 * Everything on offer comes from the SDK — the templates and app types from `projectInfo.xml`, the
 * API levels from `compilerInfo.xml`, the devices from what the SDK Manager has downloaded — so the
 * dialog cannot offer a combination the installed SDK will not build.
 *
 * Template and app type are one control rather than two. Every template belongs to exactly one app
 * type, so choosing "Watch Face — Simple with Settings" says both things, and a second combo would
 * only be a way to pick an app type with no template behind it.
 */
class ConnectIqWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    private val sdk = ConnectIqSdkService.getInstance().sdk
    private val info: ProjectInfo? = sdk?.let { ProjectInfo.read(it) }
    private val devices: List<ConnectIqDevice> = ConnectIqSdkService.getInstance().devices()

    private val choices: List<Choice> = info?.let { project ->
        val types = project.appTypes.associateBy { it.id }
        project.templates.mapNotNull { template ->
            types[template.appType]?.let { Choice(it, template) }
        }
    }.orEmpty()

    private var choice: String = choices.firstOrNull { it.appType.id == "watchface" }?.label
        ?: choices.firstOrNull()?.label
        ?: ""
    private var apiLevel: String = ""
    private var device: String = devices.firstOrNull()?.id.orEmpty()
    private var allCompatibleDevices: Boolean = false

    private class Choice(val appType: AppType, val template: ProjectTemplate) {
        val label: String = "${appType.name} — ${template.name}"
    }

    override fun setupUI(builder: Panel) {
        val info = this.info
        if (info == null || choices.isEmpty()) {
            builder.row {
                text(
                    "No Connect IQ SDK was found. The templates, the API levels and the devices all " +
                        "come from it, so there is nothing to create a project from yet.",
                )
            }
            builder.row {
                // A button rather than an instruction: the SDK is only obtainable through Garmin's
                // own application, and telling someone to go and find it is most of the way to
                // losing them.
                cell(ActionLink(OpenSdkManager.label()) { OpenSdkManager.invoke(null) })
                    // Without this the Create button stays enabled and makes an empty directory:
                    // setupProject gives up quietly, and every later message then complains about
                    // a project the plugin itself just created.
                    .validationOnApply {
                        error("Install the Connect IQ SDK before creating a project.")
                    }
            }
            return
        }

        apiLevel = defaultApiLevel(info, selected()?.appType)

        builder.row("Template:") {
            comboBox(choices.map { it.label })
                .bindItem({ choice }, { choice = it.orEmpty() })
                .columns(34)
                .comment(choices.firstOrNull { it.label == choice }?.template?.description?.lineSequence()?.first())
        }

        builder.row("Minimum API level:") {
            comboBox(info.apiLevels.map { it.toString() }.reversed())
                .bindItem({ apiLevel }, { apiLevel = it.orEmpty() })
                .comment("The oldest Connect IQ version the app will run on.")
        }

        builder.row("Device:") {
            comboBox(devices.map { it.id })
                .bindItem({ device }, { device = it.orEmpty() })
                .enabled(devices.isNotEmpty())
                .comment(
                    if (devices.isEmpty()) {
                        "No devices are downloaded, so the project will declare none."
                    } else {
                        "More can be added to <code>manifest.xml</code> later."
                    },
                )
            if (devices.isEmpty()) {
                cell(ActionLink(OpenSdkManager.label()) { OpenSdkManager.invoke(null) })
            }
        }

        builder.row("") {
            checkBox("Add every downloaded device that supports this API level")
                .bindSelected({ allCompatibleDevices }, { allCompatibleDevices = it })
        }
    }

    override fun setupProject(project: Project) {
        val sdk = this.sdk ?: return
        val info = this.info ?: return
        val selected = selected() ?: return
        val level = SdkVersion.parse(apiLevel) ?: SdkVersion.parse(defaultApiLevel(info, selected.appType)) ?: return

        val base = baseData ?: return
        val root = Path.of(base.path, base.name)
        ProjectGenerator.generate(
            sdk,
            info,
            NewProject(base.name, root, selected.appType, selected.template, level, targets(level)),
        )

        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)

        // The device the project was generated for is the one to build for.
        if (device.isNotEmpty()) MonkeyCSettings.getInstance(project).targetDevice = device
    }

    private fun selected(): Choice? = choices.firstOrNull { it.label == choice } ?: choices.firstOrNull()

    private fun targets(level: SdkVersion): List<String> =
        if (allCompatibleDevices) {
            devices.filter { it.sdkVersion == null || it.sdkVersion >= level }.map { it.id }
        } else {
            listOf(device).filter { it.isNotEmpty() }
        }

    /** The newest level the SDK offers: a new project has no reason to start with an old one. */
    private fun defaultApiLevel(info: ProjectInfo, appType: AppType?): String =
        (appType?.let { info.apiLevelsFor(it) } ?: info.apiLevels).lastOrNull()?.toString().orEmpty()
}
