package com.github.dtretyakov.monkeyc.dap

import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.lsp.SdkServerCommands
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.run.MonkeyCLaunch
import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.run.MonkeyCRunOptions
import com.github.dtretyakov.monkeyc.run.PreparedLaunch
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.Key
import java.util.concurrent.atomic.AtomicBoolean
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
            ?: throw ExecutionException(
                "Only a Connect IQ run configuration can be debugged with the Connect IQ debugger.",
            )

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

        // The SDK the build actually used, not whichever is current now. Asking again could
        // answer differently — a project can pin one — and an adapter from one SDK driving a
        // `.prg` from another fails in ways that read as a broken debugger.
        val sdk = prepared.sdk

        return startServer(
            GeneralCommandLine(SdkServerCommands.debugAdapter(sdk, ConnectIqSdkService.getInstance().java()))
                .withWorkingDirectory(prepared.root),
        ).also { watchForAWedgedSimulator(it) }
    }

    /**
     * Turns the adapter's least helpful sentence into something to act on.
     *
     * A simulator left open for hours keeps its port bound and stops answering, and the only sign
     * of it is one red line — "Failed to connect to the simulator: Timeout" — from which nothing
     * follows. Restarting the simulator fixes it every time, so the notification says so and
     * offers to do it.
     */
    private fun watchForAWedgedSimulator(handler: ProcessHandler) {
        handler.addProcessListener(
            object : ProcessListener {
                private val told = AtomicBoolean(false)

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (!event.text.contains(CANNOT_CONNECT)) return
                    // Once per session: the adapter repeats itself while it retries, and the same
                    // balloon three times is three times as easy to dismiss without reading.
                    if (!told.compareAndSet(false, true)) return
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Monkey C")
                        .createNotification(
                            "The debugger could not reach the Connect IQ simulator",
                            "A simulator that has been open for a long time keeps its port but stops " +
                                "answering. Restarting it usually settles this.",
                            NotificationType.WARNING,
                        )
                        .addAction(
                            NotificationAction.createSimpleExpiring("Restart Simulator") {
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    ConnectIqSdkService.getInstance()
                                        .sdkFor(environment.project)
                                        ?.let { Simulator.restart(it) }
                                }
                            },
                        )
                        .notify(environment.project)
                }
            },
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

    private companion object {
        /** The adapter's own wording, from `DebugServer`. */
        const val CANNOT_CONNECT = "Failed to connect to the simulator"
    }
}
