package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * The run configuration's own settings.
 *
 * One panel for every kind, with the rows that do not apply hidden: a test name means nothing to
 * an app run, and "break at launch" means nothing to a build. Which rows those are is only known
 * once a configuration is loaded, so the panel is built whole and trimmed in [resetEditorFrom].
 */
class MonkeyCSettingsEditor(private val project: Project) : SettingsEditor<MonkeyCRunConfiguration>() {

    private lateinit var panel: DialogPanel
    private lateinit var testRow: Row
    private lateinit var breakRow: Row
    private lateinit var pairingRow: Row
    private lateinit var deviceRow: Row
    private lateinit var outputRow: Row
    private lateinit var pairedRow: Row

    private var device: String = ""
    private var tests: String = ""
    private var stopAtLaunch: Boolean = false
    private var runNativePairing: Boolean = false
    private var forDevice: Boolean = false
    private var compilerArguments: String = ""
    private var outputPath: String = ""
    private var pairedProject: String = ""

    override fun createEditor(): JComponent {
        val model = MonkeyCProject.getInstance(project)
        val devices = model.primaryRoot()?.let { model.buildableDevices(it) }.orEmpty()
        val choices = DeviceChoices(devices)

        panel = panel {
            // "Device", not "Target". The chip beside Run is called Connect IQ Device, the table
            // column is Device, and Garmin's SDK Manager calls the tab Devices — this row was the
            // only place saying something else. "Target" is also taken: the platform means by it
            // where a process runs, which is Docker and SSH, not which watch is being built for.
            deviceRow = row("Device:") {
                comboBox(choices.labels)
                    .bindItem(
                        { choices.labelFor(device, forDevice) },
                        { label ->
                            device = choices.idFor(label)
                            forDevice = choices.onWatchFor(label)
                        },
                    )
                    .comment("Overrides the target chosen beside Run")
            }
            testRow = row("Tests:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText({ tests }, { tests = it })
                    .comment(
                        "Names separated by spaces. Empty runs every test in the project." +
                            "<br/>Tests cannot be debugged: the SDK's debugger never enters " +
                            "configuration mode for a test run, so no breakpoint is ever registered.",
                    )
            }
            breakRow = row {
                checkBox("Break at launch")
                    .bindSelected({ stopAtLaunch }, { stopAtLaunch = it })
                    .comment("Debug only: stops before the app's first line")
            }
            pairingRow = row {
                checkBox("Native pairing")
                    .bindSelected({ runNativePairing }, { runNativePairing = it })
                    .comment("Runs the app the way a device that ships it would")
            }
            pairedRow = row("Paired app:") {
                textFieldWithBrowseButton(
                    com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
                        .createSingleFolderDescriptor()
                        .withTitle("Paired Connect IQ Project")
                        .withDescription("The other half of a complication pair: a directory holding a manifest.xml"),
                )
                    .align(AlignX.FILL)
                    .bindText({ pairedProject }, { pairedProject = it })
                    .comment("Runs a second app in the same simulator. Debug only.")
            }
            outputRow = row("Output:") {
                textFieldWithBrowseButton(
                    com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
                        .createSingleFileOrFolderDescriptor()
                        .withTitle("Output File"),
                )
                    .align(AlignX.FILL)
                    .bindText({ outputPath }, { outputPath = it })
                    .comment("Empty writes to the project's <code>out</code> directory")
            }
            row("Compiler arguments:") {
                textField().align(AlignX.FILL).bindText({ compilerArguments }, { compilerArguments = it })
            }
        }
        return panel
    }

    override fun resetEditorFrom(configuration: MonkeyCRunConfiguration) {
        val options = configuration.options
        device = options.device
        tests = options.tests
        stopAtLaunch = options.stopAtLaunch
        runNativePairing = options.runNativePairing
        forDevice = options.forDevice
        compilerArguments = options.compilerArguments
        outputPath = options.outputPath
        pairedProject = options.pairedProject

        val kind = options.kind
        testRow.visible(kind.isTests)
        breakRow.visible(kind.launches)
        pairingRow.visible(kind == MonkeyCRunKind.APP)
        deviceRow.visible(kind.buildKind.needsDevice)
        outputRow.visible(kind == MonkeyCRunKind.EXPORT || kind == MonkeyCRunKind.BARREL)
        pairedRow.visible(kind == MonkeyCRunKind.APP)

        panel.reset()
    }

    override fun applyEditorTo(configuration: MonkeyCRunConfiguration) {
        panel.apply()
        val options = configuration.options
        options.device = device
        options.tests = tests
        options.stopAtLaunch = stopAtLaunch
        options.runNativePairing = runNativePairing
        options.forDevice = forDevice
        options.compilerArguments = compilerArguments
        options.outputPath = outputPath
        options.pairedProject = pairedProject
    }

    /**
     * The device list as it should read: a name first, the id the compiler wants in brackets.
     *
     * The option stores the id, so the two have to be mapped back and forth, and "no pin" is a
     * value of its own rather than a blank line.
     */
    /**
     * The targets a configuration can pin itself to, which are the ones the toolbar offers.
     *
     * Both destinations for every device, because the same watch is two different things to build
     * for: a simulator binary and a hardware one will not run in each other's place. The two lists
     * are prefixed rather than grouped, because a combo box has no headings.
     */
    private class DeviceChoices(devices: List<com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice>) {
        private val simulator = devices.associateBy { "Simulator:  ${it.displayName}  (${it.id})" }
        private val watch = devices.associateBy { "Watch:  ${it.displayName}  (${it.id})" }

        val labels: List<String> = listOf(FOLLOW_SELECTION) + simulator.keys + watch.keys

        fun labelFor(id: String, onWatch: Boolean): String {
            val from = if (onWatch) watch else simulator
            return from.entries.firstOrNull { it.value.id == id }?.key ?: FOLLOW_SELECTION
        }

        fun idFor(label: String?): String = (simulator[label] ?: watch[label])?.id.orEmpty()

        fun onWatchFor(label: String?): Boolean = label != null && watch.containsKey(label)

        private companion object {
            /**
             * What the configuration does when it names no target of its own.
             *
             * "Project default" rather than a description of the mechanism: the chip beside Run
             * writes the project's target, so this *is* the project default, and the platform
             * already uses that wording wherever a configuration can fall back to a project-wide
             * choice. It replaces "Whatever is selected beside Run", which was both informal and
             * an explanation of plumbing standing in for the name of a state.
             */
            const val FOLLOW_SELECTION = "Project default"
        }
    }
}
