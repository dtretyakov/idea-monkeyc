package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.run.test.MonkeyCTestMessages
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.OutputStream

/**
 * One process handler for the whole of "run this app": compile, start the simulator, push and run.
 *
 * It exists so the Run window is there from the first second, saying which step is under way. The
 * compile's own output goes to the Build window, where its errors become something to click.
 */
class MonkeyCLaunchProcessHandler(
    private val project: Project,
    private val options: MonkeyCRunOptions,
) : ProcessHandler() {

    /**
     * Turns the test runner's output into the events the test tree is built from.
     *
     * Only for a test run: an app's own output is the app's, and rewriting it would be a way to
     * lose a line of it to a coincidence.
     */
    private val testMessages = if (options.kind.isTests) MonkeyCTestMessages() else null

    /** The app in the simulator, once there is one. Until then, there is nothing to kill. */
    @Volatile
    private var running: OSProcessHandler? = null

    /**
     * Set when the user presses Stop.
     *
     * Stop can arrive while the compiler is still working, when there is no process to kill yet.
     * The build then finishes on its own — but the app must not be launched afterwards, which is
     * what the user asked for.
     */
    @Volatile
    private var stopped = false

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread { launch() }
    }

    private fun launch() {
        try {
            if (options.kind.launches && !options.forDevice) {
                runInSimulator()
            } else {
                buildOnly()
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            // Everything, not only ExecutionException: a build can fail on a read-only directory
            // or a full disk, and an escaping exception would leave the Run window showing a live
            // process with an empty console and no way to see why.
            notifyTextAvailable("\n${e.message ?: e.javaClass.simpleName}\n", ProcessOutputTypes.STDERR)
            if (e !is ExecutionException) LOG.warn("Could not run the Connect IQ app", e)
            notifyProcessTerminated(1)
        }
    }

    /** A configuration that builds and stops: the console reports where the artifact went. */
    private fun buildOnly() {
        val built = MonkeyCLaunch.build(project, options, ::report)
        if (stopped) return

        val verb = if (options.kind == MonkeyCRunKind.EXPORT) "Exported to" else "Built"
        if (built.upToDate) {
            notifyTextAvailable("\n${built.output} is up to date.\n", ProcessOutputTypes.SYSTEM)
        } else {
            notifyTextAvailable("\n$verb ${built.output}\n", ProcessOutputTypes.SYSTEM)
        }
        if (options.forDevice) {
            notifyTextAvailable(
                "Copy it to GARMIN/APPS on the watch over USB to install it.\n",
                ProcessOutputTypes.SYSTEM,
            )
        }
        notifyProcessTerminated(0)
    }

    private fun runInSimulator() {
        if (options.pairedProject.isNotEmpty()) {
            // `monkeydo` pushes one app and has no second slot; only the debug adapter takes an
            // additionalPrg. Saying so beats silently running half of a complication.
            throw ExecutionException(
                "A complication pair can only be started under the debugger: monkeydo takes one " +
                    "app. Use Debug, or clear the paired app in this configuration.",
            )
        }

        val prepared = MonkeyCLaunch.prepare(project, options, ::report)
        if (stopped) return

        val java = ConnectIqSdkService.getInstance().java().toString()
        val handler = OSProcessHandler(MonkeyDo.commandLine(prepared, java, options))
        running = handler

        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (testMessages != null && outputType === ProcessOutputTypes.STDOUT) {
                        val translated = testMessages.translate(event.text)
                        if (translated.isNotEmpty()) notifyTextAvailable(translated, outputType)
                    } else {
                        notifyTextAvailable(event.text, outputType)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    // A test still open here never reported a result, and the tree would show it
                    // running for ever; this is the last chance to close it.
                    testMessages?.flush()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { notifyTextAvailable(it, ProcessOutputTypes.STDOUT) }
                    notifyProcessTerminated(event.exitCode)
                }
            },
        )

        notifyTextAvailable("\nRunning on ${prepared.device}...\n\n", ProcessOutputTypes.SYSTEM)
        handler.startNotify()
    }

    private fun report(step: String) = notifyTextAvailable("$step\n", ProcessOutputTypes.SYSTEM)

    override fun destroyProcessImpl() {
        stopped = true
        val handler = running
        if (handler == null) {
            // Stopped during the build. The compiler finishes on its own, but `stopped` keeps the
            // app from being pushed to the simulator once it does.
            notifyProcessTerminated(1)
        } else {
            handler.destroyProcess()
        }
    }

    override fun detachProcessImpl() {
        stopped = true
        running?.detachProcess() ?: notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = running?.processInput

    private companion object {
        val LOG = logger<MonkeyCLaunchProcessHandler>()
    }
}
