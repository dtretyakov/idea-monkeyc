package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class MonkeyCSettingsEditor(private val project: Project) : SettingsEditor<MonkeyCRunConfiguration>() {

    private lateinit var panel: DialogPanel
    private var device: String = ""
    private var runTests: Boolean = false
    private var testName: String = ""
    private var stopAtLaunch: Boolean = false
    private var runNativePairing: Boolean = false
    private var compilerArguments: String = ""

    override fun createEditor(): JComponent {
        val model = MonkeyCProject.getInstance(project)
        val devices = model.primaryRoot()?.let { model.buildableDevices(it) }.orEmpty()

        panel = panel {
            row("Device:") {
                comboBox(listOf("") + devices.map { it.id })
                    .bindItem({ device }, { device = it.orEmpty() })
                    .comment("Empty uses the project's target device.")
            }
            row {
                checkBox("Run unit tests").bindSelected({ runTests }, { runTests = it })
            }
            row("Test:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText({ testName }, { testName = it })
                    .comment("A single test to run. Empty runs them all.")
            }
            row {
                checkBox("Break at launch")
                    .bindSelected({ stopAtLaunch }, { stopAtLaunch = it })
                    .comment("Debug only: stops before the app's first line.")
            }
            row {
                checkBox("Native pairing")
                    .bindSelected({ runNativePairing }, { runNativePairing = it })
                    .comment("Runs the app the way a device that ships it would.")
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
        runTests = options.runTests
        testName = options.testName
        stopAtLaunch = options.stopAtLaunch
        runNativePairing = options.runNativePairing
        compilerArguments = options.compilerArguments
        panel.reset()
    }

    override fun applyEditorTo(configuration: MonkeyCRunConfiguration) {
        panel.apply()
        val options = configuration.options
        options.device = device
        options.runTests = runTests
        options.testName = testName
        options.stopAtLaunch = stopAtLaunch
        options.runNativePairing = runNativePairing
        options.compilerArguments = compilerArguments
    }
}
