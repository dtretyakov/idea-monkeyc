package com.github.dtretyakov.monkeyc.dap

import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.lsp.SdkServerCommands
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.run.MonkeyCLaunch
import com.github.dtretyakov.monkeyc.run.MonkeyCRunOptions
import com.github.dtretyakov.monkeyc.run.PreparedLaunch
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressManager
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import kotlin.io.path.exists

/**
 * The debug adapter Garmin ships in the Connect IQ SDK.
 *
 * It is the same program the official VS Code extension runs — the extension bundles its own copy,
 * but the SDK has one too, so nothing here depends on VS Code being installed. Which jar it comes
 * out of is not arbitrary; see [SdkServerCommands.debugAdapter].
 */
class MonkeyCDebugAdapterDescriptor(
    private val options: RunConfigurationOptions,
    private val environment: ExecutionEnvironment,
    serverDefinition: DebugAdapterServerDefinition?,
) : DebugAdapterDescriptor(options, environment, serverDefinition) {

    private lateinit var prepared: PreparedLaunch

    /**
     * Compiles, starts the simulator, then starts the adapter.
     *
     * The order is forced: the adapter's `launch` names a `.prg` and expects the simulator to be
     * listening already — it pushes the app into a running simulator rather than starting one.
     */
    override fun startServer(): ProcessHandler {
        val monkeyCOptions = options as? MonkeyCRunOptions
            ?: throw ExecutionException("This debug configuration is not a Connect IQ one.")

        val indicator = ProgressManager.getInstance().progressIndicator
        prepared = MonkeyCLaunch.prepare(environment.project, monkeyCOptions) { step ->
            indicator?.text = step
        }

        if (!prepared.debugXml.exists()) {
            throw ExecutionException(
                "The build produced no ${prepared.debugXml.fileName}, and the debugger needs it " +
                    "to map the executable back to source.",
            )
        }

        val sdk = ConnectIqSdkService.getInstance().sdk
            ?: throw ExecutionException("No Connect IQ SDK found.")

        return startServer(
            GeneralCommandLine(SdkServerCommands.debugAdapter(sdk, ConnectIqSdkService.getInstance().java()))
                .withWorkingDirectory(prepared.root),
        )
    }

    /**
     * The `launch` arguments. The names are the adapter's, taken from the configuration the VS Code
     * extension sends it.
     */
    override fun getDapParameters(): MutableMap<String, Any> {
        val monkeyCOptions = options as MonkeyCRunOptions
        return buildMap {
            put("type", "monkeyc")
            put("request", "launch")
            put("name", environment.runProfile.name)
            put("prg", prepared.prg.toString())
            put("prgDebugXml", prepared.debugXml.toString())
            put("device", prepared.device)
            put("stopAtLaunch", monkeyCOptions.stopAtLaunch)
            if (monkeyCOptions.runTests) {
                put("runTests", true)
                // Only when a subset was picked: the adapter takes an empty array to mean
                // "run nothing at all", and then reports no results and no error.
                monkeyCOptions.testNames.takeIf { it.isNotEmpty() }?.let { put("tests", it) }
            }
            if (monkeyCOptions.runNativePairing) put("runNativePairing", true)
            // A complication pair: the simulator loads both, and the debugger stops in either.
            prepared.paired?.let {
                put("additionalPrg", it.output.toString())
                put("additionalPrgDebugXml", ProjectLayout.debugXml(it.output).toString())
            }
            // App settings the build produced, so the simulator starts with them already set.
            prepared.settingsJson?.let { put("settingsJson", it.toString()) }
        }.toMutableMap()
    }

    override fun getFileType(): FileType = MonkeyCFileType
}
