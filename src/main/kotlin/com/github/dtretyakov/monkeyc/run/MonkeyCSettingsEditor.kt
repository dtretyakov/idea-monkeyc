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
    private lateinit var deviceBuildRow: Row

    private var device: String = ""
    private var testName: String = ""
    private var stopAtLaunch: Boolean = false
    private var runNativePairing: Boolean = false
    private var forDevice: Boolean = false
    private var compilerArguments: String = ""

    override fun createEditor(): JComponent {
        val model = MonkeyCProject.getInstance(project)
        val devices = model.primaryRoot()?.let { model.buildableDevices(it) }.orEmpty()
        val choices = DeviceChoices(devices)

        panel = panel {
            row("Device:") {
                comboBox(choices.labels)
                    .bindItem({ choices.labelFor(device) }, { device = choices.idFor(it) })
                    .comment("Pins this configuration to one device. Otherwise it follows the device chosen next to the Run button.")
            }
            testRow = row("Test:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText({ testName }, { testName = it })
                    .comment("A single test to run. Empty runs them all.")
            }
            breakRow = row {
                checkBox("Break at launch")
                    .bindSelected({ stopAtLaunch }, { stopAtLaunch = it })
                    .comment("Debug only: stops before the app's first line.")
            }
            pairingRow = row {
                checkBox("Native pairing")
                    .bindSelected({ runNativePairing }, { runNativePairing = it })
                    .comment("Runs the app the way a device that ships it would.")
            }
            deviceBuildRow = row {
                checkBox("Build for the watch, not the simulator")
                    .bindSelected({ forDevice }, { forDevice = it })
                    .comment(
                        "Produces a <code>.prg</code> to copy to <code>GARMIN/APPS</code> over USB. " +
                            "A watch build will not start in the simulator, and the other way round.",
                    )
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
        testName = options.testName
        stopAtLaunch = options.stopAtLaunch
        runNativePairing = options.runNativePairing
        forDevice = options.forDevice
        compilerArguments = options.compilerArguments

        val kind = options.kind
        testRow.visible(kind.isTests)
        breakRow.visible(kind.launches)
        pairingRow.visible(kind == MonkeyCRunKind.APP)
        deviceBuildRow.visible(kind == MonkeyCRunKind.BUILD)

        panel.reset()
    }

    override fun applyEditorTo(configuration: MonkeyCRunConfiguration) {
        panel.apply()
        val options = configuration.options
        options.device = device
        options.testName = testName
        options.stopAtLaunch = stopAtLaunch
        options.runNativePairing = runNativePairing
        options.forDevice = forDevice
        options.compilerArguments = compilerArguments
    }

    /**
     * The device list as it should read: a name first, the id the compiler wants in brackets.
     *
     * The option stores the id, so the two have to be mapped back and forth, and "no pin" is a
     * value of its own rather than a blank line.
     */
    private class DeviceChoices(devices: List<com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice>) {
        private val byLabel = devices.associateBy { "${it.displayName}  (${it.id})" }

        val labels: List<String> = listOf(FOLLOW_SELECTION) + byLabel.keys

        fun labelFor(id: String): String =
            byLabel.entries.firstOrNull { it.value.id == id }?.key ?: FOLLOW_SELECTION

        fun idFor(label: String?): String = byLabel[label]?.id.orEmpty()

        private companion object {
            const val FOLLOW_SELECTION = "Whatever is selected"
        }
    }
}
