package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment
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
        // The environment, read once and quoted field by field. It used to be rendered as a
        // checklist of its own at the top of this page — six lines that mostly said everything was
        // fine, sitting where the controls should be, and repeating what four fields below already
        // had a place to say. Each line now belongs to the control that fixes it.
        val environment = ConnectIqEnvironment.check(project)
        fun status(concern: ConnectIqEnvironment.Concern): String =
            ConnectIqEnvironment.of(environment, concern)?.detail.orEmpty()

        val sdkChoices = SdkChoices(ConnectIqSdk.installed(), settings.sdkPath, ConnectIqSdk.detect()?.root)

        return panel {
            group("SDK (This Computer)") {
                row {
                    // Where SDKs and devices come from, with what it says about itself under it,
                    // and Reload beside it because nothing the manager changes reaches the IDE
                    // until something re-reads.
                    link(OpenSdkManager.label()) { OpenSdkManager.invoke(project) }
                    button("Reload") { sdkService.refresh() }
                }.rowComment(status(ConnectIqEnvironment.Concern.SDK_MANAGER))
                row("Location:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Connect IQ SDK")
                            .withDescription("The directory holding bin/, inside the SDK Manager's Sdks folder"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(app::sdkPath)
                        // What is actually in use, not only how to change it. This is the line
                        // the checklist used to carry, and it is more use here: the field it
                        // describes is the field that fixes it.
                        .comment(
                            "${status(ConnectIqEnvironment.Concern.SDK)}<br/>" +
                                "${status(ConnectIqEnvironment.Concern.DEVICES)}. Leave empty to " +
                                "follow the SDK Manager's own choice, which it records in " +
                                "<code>current-sdk.cfg</code>.",
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
                    comment("Stored with the project, in <code>.idea/monkeyc.xml</code>")
                }
                row("SDK:") {
                    comboBox(sdkChoices.labels)
                        .bindItem(
                            { sdkChoices.labelFor(settings.sdkPath) },
                            { settings.sdkPath = sdkChoices.pathFor(it) },
                        )
                        .comment(
                            "Which SDK this project builds with. <b>${SdkChoices.FOLLOW}</b> is the " +
                                "one the SDK Manager has made current, and it changes when the " +
                                "manager updates. Pinning one keeps a shipped app building the way " +
                                "it shipped.",
                        )
                }
                row("Developer key:") {
                    val field = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileDescriptor("der")
                            .withTitle("Developer Key"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(settings::developerKeyPath)
                        .comment(
                            "${status(ConnectIqEnvironment.Concern.DEVELOPER_KEY)}. Leave empty to " +
                                "use the key the SDK Manager generated",
                        )
                        .validationOnApply { field ->
                            val given = field.text.trim().takeIf { it.isNotEmpty() }
                            given?.let { path ->
                                DeveloperKey.problemWith(Path.of(path))?.let { error(it) }
                            }
                        }

                    button("Generate…") {
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

            group("Code Intelligence") {
                row {
                    checkBox("Analyse the project as it is edited")
                        .bindSelected(settings::liveAnalysis)
                        .comment(
                            "${status(ConnectIqEnvironment.Concern.LANGUAGE_SERVER)}. Runs the " +
                                "language server the SDK ships, which is what completion, " +
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
                        .comment("Passes <code>-w</code> to the compiler and asks the server to publish warnings")
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
    internal class SdkChoices(installed: List<Path>, pinned: String, current: Path? = null) {
        private val byLabel = installed.associateBy { it.name } +
            (
                pinned.trim()
                    .takeIf { it.isNotEmpty() && installed.none { sdk -> sdk.toString() == it } }
                    ?.let { mapOf("$it  (not on this machine)" to Path.of(it)) }
                    ?: emptyMap()
                )

        /**
         * The first entry names the SDK it currently resolves to, when that is known.
         *
         * "Current SDK" alone is a promise about the future; "Current SDK (connectiq-sdk-mac-9.2.0)"
         * also answers the question the user actually has, which is what they are about to build
         * with today.
         */
        val labels: List<String> = listOf(followLabel(current)) + byLabel.keys

        fun labelFor(path: String): String =
            byLabel.entries.firstOrNull { it.value.toString() == path.trim() }?.key ?: labels.first()

        fun pathFor(label: String?): String = byLabel[label]?.toString().orEmpty()

        companion object {
            /**
             * Garmin's own name for it, not ours.
             *
             * The SDK Manager calls the SDK it has made active the "Current SDK", and writes it to
             * `current-sdk.cfg`. This used to read "Follow the SDK Manager", which describes what
             * the plugin does about it and leaves the user to work out that the two are the same
             * thing. Borrowing the vocabulary of the tool the user already has open costs nothing
             * and removes the translation step.
             */
            const val FOLLOW = "Current SDK"

            fun followLabel(current: Path?): String =
                current?.let { "$FOLLOW  (${it.name})" } ?: FOLLOW
        }
    }

    /**
     * The device list as it should read: a name first, the id the compiler wants in brackets.
     *
     * The setting stores the id, so the two have to be mapped back and forth — and an unset device
     * is a value of its own rather than a blank line.
     */
}
