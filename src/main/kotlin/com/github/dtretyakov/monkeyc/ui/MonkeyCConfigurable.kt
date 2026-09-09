package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.project.DebugLogLevel
import com.github.dtretyakov.monkeyc.project.MonkeyCAppSettings
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.OptimizationLevel
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.project.TypeCheckLevel
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.sdk.DeveloperKey
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.exists

/**
 * Settings | Languages &amp; Frameworks | Monkey C.
 *
 * Two scopes on one page, because the split is not something a user should have to think about:
 * where the SDK and the JDK live belongs to the machine, everything else belongs to the project
 * and can be committed with it.
 */
class MonkeyCConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Monkey C", "settings.monkeyc") {

    override fun createPanel(): DialogPanel {
        val app = MonkeyCAppSettings.getInstance()
        val settings = MonkeyCSettings.getInstance(project)
        val model = MonkeyCProject.getInstance(project)
        val sdkService = ConnectIqSdkService.getInstance()

        val sdk = sdkService.sdk
        // Kept apart on purpose: an empty device list means one thing when there is a project to
        // read the manifest from and quite another when there is not, and the page used to give
        // the same advice — go and download devices — for both.
        val root = model.primaryRoot()
        val devices = root?.let { model.buildableDevices(it) }.orEmpty()
        val target = DeviceChoices(devices)
        val sdkChoices = SdkChoices(ConnectIqSdk.installed(), settings.sdkPath)

        lateinit var environment: EnvironmentPanel

        return panel {
            group("Setup") {
                row {
                    // The whole checklist rather than a one-line summary: the six things that have
                    // to be in place are met one at a time by a newcomer, and meeting them one at a
                    // time is exactly what makes a first run feel like a series of refusals.
                    environment = EnvironmentPanel(project) { generateDeveloperKey(project) }
                    cell(environment)
                }
                row {
                    button("Reload") {
                        sdkService.refresh()
                        environment.refresh()
                    }
                    // Beside Reload rather than only on a broken line. The SDK Manager is where
                    // devices are downloaded and SDKs are upgraded — both ordinary things to do on
                    // a machine where nothing is wrong — and until now it appeared only as a repair
                    // link on a checklist item that had failed. Reload beside it because what the
                    // manager changed is invisible to the IDE until something re-reads it.
                    link(OpenSdkManager.label()) {
                        OpenSdkManager.invoke(project)
                    }
                }
            }

            group("SDK (this computer)") {
                row("Location:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Connect IQ SDK")
                            .withDescription("The directory holding bin/, inside the SDK Manager's Sdks folder"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(app::sdkPath)
                        .comment(
                            "Leave empty to follow the SDK Manager's own choice, which it records " +
                                "in <code>current-sdk.cfg</code>.",
                        )
                        // Without this a typo is indistinguishable from having no SDK at all: every
                        // surface says "No Connect IQ SDK found" and none of them says where it looked.
                        .validationOnApply { field ->
                            val given = field.text.trim().takeIf { it.isNotEmpty() }
                            given?.let { path ->
                                if (!Path.of(path).resolve("bin").exists()) {
                                    error("No bin directory here, so this is not a Connect IQ SDK.")
                                } else {
                                    null
                                }
                            }
                        }
                }
                row("MTP tool:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                            .withTitle("mtp-rs"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(app::mtpToolPath)
                        .comment(
                            "<code>mtp-rs</code>, used to install a build on a watch that speaks " +
                                "MTP — which current Garmin devices do, appearing under no volume " +
                                "at all. Leave empty to look for it. Older watches mount as a disk " +
                                "and need none of this.",
                        )
                }
                row("Java:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                            .withTitle("Java"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(app::javaPath)
                        .comment("A JDK home or a <code>java</code> executable. Leave empty to use the IDE's own.")
                }
            }

            group("Project") {
                row {
                    comment("Stored with the project, in <code>.idea/monkeyc.xml</code>.")
                }
                row("SDK:") {
                    comboBox(sdkChoices.labels)
                        .bindItem(
                            { sdkChoices.labelFor(settings.sdkPath) },
                            { settings.sdkPath = sdkChoices.pathFor(it) },
                        )
                        .comment(
                            "Which SDK this project builds with. <b>${SdkChoices.FOLLOW}</b> means " +
                                "whichever the SDK Manager has made current, which changes under you " +
                                "when it updates. Pinning one keeps a shipped app building the way it " +
                                "shipped.",
                        )
                }
                row("Target device:") {
                    comboBox(target.labels)
                        .bindItem(
                            { target.labelFor(settings.targetDevice) },
                            { settings.targetDevice = target.idFor(it) },
                        )
                        .enabled(devices.isNotEmpty())
                        .comment(
                            when {
                                root == null ->
                                    "No Connect IQ project is open, so there is no manifest to read " +
                                        "the devices from."

                                devices.isEmpty() ->
                                    "None of the devices this project declares is downloaded. " +
                                        "Get them with the SDK Manager."

                                else ->
                                    "What Build and Run target. A run configuration can name a different one."
                            },
                        )
                }
                row("Developer key:") {
                    val field = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileDescriptor("der")
                            .withTitle("Developer Key"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(settings::developerKeyPath)
                        .comment("Leave empty to use the key the SDK Manager generated.")
                        .validationOnApply { field ->
                            val given = field.text.trim().takeIf { it.isNotEmpty() }
                            given?.let { path ->
                                DeveloperKey.problemWith(Path.of(path))?.let { error(it) }
                            }
                        }

                    button("Generate...") {
                        generateDeveloperKey(project)?.let { field.component.text = it.toString() }
                    }
                }
                row("Jungle files:") {
                    // Expandable: several jungles on one line separated by semicolons is how the
                    // compiler wants them and not how anyone wants to read them.
                    cell(ExpandableTextField({ it.split(';').map(String::trim) }, { it.joinToString(";") }))
                        .columns(COLUMNS_LARGE)
                        .bindText(settings::jungleFiles)
                        .comment(
                            "Separated by <code>;</code>, relative to the project root. " +
                                "Empty means <code>${ProjectLayout.DEFAULT_JUNGLE}</code>.",
                        )
                        // A jungle the compiler cannot find is not an error the user ever sees:
                        // the language server logs "does not exist" to a console nobody opens and
                        // then indexes nothing at all.
                        .validationOnApply { field -> missingJungle(model, field.text)?.let { error(it) } }
                }
            }.enabled(sdk != null)

            group("Code intelligence") {
                row {
                    checkBox("Analyse the project as it is edited")
                        .bindSelected(settings::liveAnalysis)
                        .comment(
                            "Runs the language server the SDK ships, which is what completion, " +
                                "diagnostics and navigation come from. It is a second JVM that " +
                                "compiles the whole project in the background; turning it off " +
                                "leaves building, running, debugging and highlighting untouched.",
                        )
                }
            }.enabled(sdk != null)

            group("Compiler") {
                row {
                    comment(
                        "<b>Default</b> leaves the flag out, so the compiler's own default applies — " +
                            "which is not the same as <b>Off</b> or <b>None</b>.",
                    )
                }
                row("Type checking:") {
                    comboBox(TypeCheckLevel.entries.map { it.display })
                        .bindItem(settings::typeCheckLevel.toNullableProperty())
                }
                row("Optimization:") {
                    comboBox(OptimizationLevel.entries.map { it.display })
                        .bindItem(settings::optimizationLevel.toNullableProperty())
                }
                row("Debug logging:") {
                    comboBox(DebugLogLevel.entries.map { it.display })
                        .bindItem(settings::debugLogLevel.toNullableProperty())
                }
                row {
                    checkBox("Report warnings")
                        .bindSelected(settings::compilerWarnings)
                        .comment("Passes <code>-w</code> to the compiler and asks the server to publish warnings.")
                }
                row("Extra arguments:") {
                    textField()
                        .columns(COLUMNS_LARGE)
                        .bindText(settings::compilerOptions)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        // The server reads all of this once, at initialize; it has to be told to start over.
        project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
    }

    /**
     * Writes a new signing key where the user chooses.
     *
     * Garmin documents this as a pair of openssl commands, but the file is a 4096-bit RSA private
     * key in PKCS#8 DER and the JVM can write one — so this works on a machine with no openssl,
     * which on Windows is most of them.
     */
    private fun generateDeveloperKey(project: Project): Path? {
        val chosen = FileChooserFactory.getInstance()
            .createSaveFileDialog(
                FileSaverDescriptor("Generate Developer Key", "Where to write the new signing key", "der"),
                project,
            )
            .save("developer_key.der")
            ?.file
            ?.toPath()
            ?: return null

        return runCatching { DeveloperKey.generate(chosen) }
            .onFailure {
                Messages.showErrorDialog(project, it.message ?: "Could not write the key.", "Developer Key")
            }
            .getOrNull()
    }

    /** The first jungle file that is named but not there, if any. */
    private fun missingJungle(model: MonkeyCProject, configured: String): String? {
        val root = model.primaryRoot() ?: return null
        val missing = ProjectLayout.jungleFiles(root, configured).firstOrNull { !it.exists() } ?: return null
        return "No such file: ${FileUtil.getLocationRelativeToUserHome(missing.toString())}"
    }

    /**
     * The SDKs on this machine, and the choice to follow the SDK Manager instead.
     *
     * A pin the machine cannot honour is kept in the list rather than dropped from it. These
     * settings are committed, so the path may be a colleague's; silently showing "Follow the SDK
     * Manager" would make it look as though nobody had pinned anything, and the next save would
     * throw their choice away.
     */
    internal class SdkChoices(installed: List<Path>, pinned: String) {
        private val byLabel = installed.associateBy { it.name } +
            (
                pinned.trim()
                    .takeIf { it.isNotEmpty() && installed.none { sdk -> sdk.toString() == it } }
                    ?.let { mapOf("$it  (not on this machine)" to Path.of(it)) }
                    ?: emptyMap()
                )

        val labels: List<String> = listOf(FOLLOW) + byLabel.keys

        fun labelFor(path: String): String =
            byLabel.entries.firstOrNull { it.value.toString() == path.trim() }?.key ?: FOLLOW

        fun pathFor(label: String?): String = byLabel[label]?.toString().orEmpty()

        companion object {
            const val FOLLOW = "Follow the SDK Manager"
        }
    }

    /**
     * The device list as it should read: a name first, the id the compiler wants in brackets.
     *
     * The setting stores the id, so the two have to be mapped back and forth — and an unset device
     * is a value of its own rather than a blank line.
     */
    private class DeviceChoices(devices: List<ConnectIqDevice>) {
        private val byLabel = devices.associateBy { "${it.displayName}  (${it.id})" }

        val labels: List<String> = listOf(NOT_SET) + byLabel.keys

        fun labelFor(id: String): String =
            byLabel.entries.firstOrNull { it.value.id == id }?.key ?: NOT_SET

        fun idFor(label: String?): String = byLabel[label]?.id.orEmpty()

        private companion object {
            const val NOT_SET = "Not set"
        }
    }
}
