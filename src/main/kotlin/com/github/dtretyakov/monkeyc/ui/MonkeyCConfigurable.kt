package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.DebugLogLevel
import com.github.dtretyakov.monkeyc.project.MonkeyCAppSettings
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.OptimizationLevel
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.project.TypeCheckLevel
import com.github.dtretyakov.monkeyc.sdk.DeveloperKey
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import java.nio.file.Path
import javax.swing.JLabel

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

        lateinit var detected: JLabel

        return panel {
            group("SDK") {
                row {
                    detected = label(describeSdk()).component
                }
                row("Location:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Connect IQ SDK")
                            .withDescription("The directory holding bin/, inside the SDK Manager's Sdks folder"),
                    )
                        .align(AlignX.FILL)
                        .bindText(app::sdkPath)
                        .comment(
                            "Leave empty to follow the SDK Manager's own choice, which it records " +
                                "in <code>current-sdk.cfg</code>.",
                        )
                }
                row("Java:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                            .withTitle("Java"),
                    )
                        .align(AlignX.FILL)
                        .bindText(app::javaPath)
                        .comment("A JDK home or a <code>java</code> executable. Leave empty to use the IDE's own.")
                }
                row {
                    button("Reload") {
                        sdkService.refresh()
                        detected.text = describeSdk()
                    }
                }
            }

            group("Project") {
                row("Target device:") {
                    val devices = model.primaryRoot()?.let { model.buildableDevices(it) }.orEmpty()
                    comboBox(listOf("") + devices.map { it.id })
                        .bindItem(settings::targetDevice.toNullableProperty())
                        .comment(
                            devices.joinToString(", ", limit = 6) { "${it.displayName} (${it.id})" }
                                .ifEmpty { "No devices. Download some with the SDK Manager." },
                        )
                }
                row("Developer key:") {
                    val field = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileDescriptor("der")
                            .withTitle("Developer Key"),
                    )
                        .align(AlignX.FILL)
                        .bindText(settings::developerKeyPath)
                        .comment("Leave empty to use the key the SDK Manager generated.")

                    button("Generate...") {
                        generateDeveloperKey(project)?.let { field.component.text = it.toString() }
                    }
                }
                row("Jungle files:") {
                    textField()
                        .align(AlignX.FILL)
                        .bindText(settings::jungleFiles)
                        .comment(
                            "Separated by <code>;</code>, relative to the project root. " +
                                "Empty means <code>${ProjectLayout.DEFAULT_JUNGLE}</code>.",
                        )
                }
            }

            group("Compiler") {
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
                        .align(AlignX.FILL)
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

    private fun describeSdk(): String {
        val sdk = ConnectIqSdkService.getInstance().sdk
            ?: return "No Connect IQ SDK found. Install one with Garmin's SDK Manager."
        val devices = ConnectIqSdkService.getInstance().devices().size
        val version = sdk.version?.let { "SDK $it" } ?: "SDK of unknown version"
        val server = if (sdk.hasLanguageServer) "" else " — no language server, so no code intelligence"
        return "$version at ${sdk.root}, $devices devices downloaded$server"
    }
}
