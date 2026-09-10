package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.project.DebugLogLevel
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
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.Row
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

        // Read once, when the page opens: a version is a small file, and the alternative is
        // reading it again on every repaint of a combo.
        val versions = ConnectIqSdk.installed().associateWith { ConnectIqSdk.at(it).version?.toString() }
        val sdkChoices = SdkChoices(
            installed = ConnectIqSdk.installed(),
            pinned = settings.sdkPath,
            current = ConnectIqSdk.detect()?.root,
            version = { versions[it] ?: ConnectIqSdk.at(it).version?.toString() },
        )

        return panel {
            group("Connect IQ") {
                // One SDK control, not two. There used to be a machine-wide path field in a group
                // of its own and a project combo down here, which between them answered "where is
                // the SDK" twice and left the user to work out which one won. The combo answers it
                // once: the manager's current one, any of the installed ones, or Custom — which
                // brings back a path field, for this project only, right underneath.
                lateinit var location: Row
                row("SDK:") {
                    val combo = comboBox(sdkChoices.labels)
                        .bindItem(
                            { sdkChoices.labelFor(settings.sdkPath) },
                            { chosen ->
                                // Custom keeps whatever the field below holds; every other entry
                                // owns the value outright.
                                if (chosen != SdkChoices.CUSTOM) {
                                    settings.sdkPath = sdkChoices.pathFor(chosen)
                                }
                            },
                        )
                        // The manager goes in the sentence under the control rather than beside
                        // it: it is where more SDKs and devices come from, which is a remark about
                        // the line above and not a second thing to do to it.
                        // `<a>` without an href: the platform refuses `<a href=''>` outright —
                        // "empty href like <a href=''> is denied" — and threw a UiDslException
                        // every time this page was built, which is every time a project window
                        // opens.
                        .comment(
                            "${ConnectIqEnvironment.sentence(environment, ConnectIqEnvironment.Concern.DEVICES)} " +
                                "<a>${OpenSdkManager.label()}</a>",
                            action = HyperlinkEventAction { OpenSdkManager.invoke(project) },
                        )
                    // Stays beside the control, because it acts on it: nothing the manager changes
                    // reaches the IDE until something re-reads.
                    button("Reload") { sdkService.refresh() }

                    // Shown for Custom and hidden otherwise. A listener rather than the DSL's own
                    // predicate helper, which this platform version does not have.
                    combo.component.addActionListener {
                        location.visible(combo.component.selectedItem == SdkChoices.CUSTOM)
                    }
                }
                location = row("Location:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Connect IQ SDK")
                            .withDescription("The directory holding bin/"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(settings::sdkPath)
                        // Without this a typo is indistinguishable from having no SDK at all: every
                        // surface says "No Connect IQ SDK found" and none of them says where it looked.
                        .validationOnApply { field ->
                            val given = field.text.trim().takeIf { it.isNotEmpty() }
                            given?.let { path ->
                                if (!Path.of(path).resolve("bin").exists()) {
                                    error("No bin directory here, so this is not a Connect IQ SDK")
                                } else {
                                    null
                                }
                            }
                        }
                }
                // The state the page opens in, before anything is chosen.
                location.visible(sdkChoices.labelFor(settings.sdkPath) == SdkChoices.CUSTOM)
                row("Developer key:") {
                    val field = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileDescriptor("der")
                            .withTitle("Developer Key"),
                    )
                        .columns(COLUMNS_LARGE)
                        .bindText(settings::developerKeyPath)
                        .comment(status(ConnectIqEnvironment.Concern.DEVELOPER_KEY))
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
                    checkBox("Analyze the project as it is edited")
                        .bindSelected(settings::liveAnalysis)
                        // What the switch costs, in one line. It used to be four, three of
                        // which explained the implementation: a second JVM, background compilation,
                        // what the server is called. None of that changes the decision.
                        .comment(
                            "Completion and diagnostics come from the SDK's language server. " +
                                "Turning it off leaves building and debugging untouched.",
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
    internal class SdkChoices(
        installed: List<Path>,
        pinned: String,
        current: Path? = null,
        /**
         * The version in an SDK's `bin/version.txt`, or null when it cannot be read.
         *
         * Passed in rather than read here so this stays a rule that can be tested without an SDK
         * on disk — and so the settings page reads each file once, when it opens.
         */
        version: (Path) -> String? = { null },
    ) {
        private val byLabel = installed.associateBy { describe(it, version(it), installed, version) }

        /**
         * The first entry names the SDK it currently resolves to, when that is known.
         *
         * "Current SDK" alone is a promise about the future; "Current SDK (connectiq-sdk-mac-9.2.0)"
         * also answers the question the user actually has, which is what they are about to build
         * with today.
         */
        val labels: List<String> = listOf(followLabel(current, version)) + byLabel.keys + CUSTOM

        /**
         * Empty follows the manager; a path we know is its own entry; anything else is Custom.
         *
         * A pin this machine does not have used to be its own label marked "(not on this machine)".
         * It is Custom now, and the path is on screen in the field below rather than inside a
         * label — same information, in a place it can be corrected.
         */
        fun labelFor(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return labels.first()
            return byLabel.entries.firstOrNull { it.value.toString() == trimmed }?.key ?: CUSTOM
        }

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

            /**
             * An SDK the manager does not know about — unpacked by hand, or a colleague's.
             *
             * A state rather than an action, which is the correction: this was briefly "Add SDK…",
             * an action item living in the same list as the values, so choosing it left the combo
             * reading "Add SDK…" and cancelling the chooser left it there for good. Selecting
             * Custom reveals the path instead, where it stays visible and can be edited or copied.
             */
            const val CUSTOM = "Custom…"

            /**
             * A version number, because that is what anyone comparing two SDKs is comparing.
             *
             * The directory name is what this used to show — `connectiq-sdk-mac-9.2.0-2026-06-09-
             * 92a1605b2` — which is the version with a date, a platform and a build hash wrapped
             * around it, and in a combo of that width the version is the part that gets truncated
             * away. Falls back to the directory name when there is no `version.txt` to read, and
             * keeps it alongside when two SDKs report the same version, so the list never has two
             * entries the map cannot tell apart.
             */
            fun describe(sdk: Path, version: String?, all: List<Path>, versionOf: (Path) -> String?): String {
                if (version == null) return sdk.name
                val sameVersion = all.count { versionOf(it) == version } > 1
                return if (sameVersion) "$version  (${sdk.name})" else version
            }

            fun followLabel(current: Path?, version: (Path) -> String? = { null }): String =
                current?.let { "$FOLLOW  (${version(it) ?: it.name})" } ?: FOLLOW
        }
    }

}
