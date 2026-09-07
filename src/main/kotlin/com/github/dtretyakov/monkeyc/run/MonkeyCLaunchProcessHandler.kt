package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
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
            val prepared = MonkeyCLaunch.prepare(project, options) { step ->
                notifyTextAvailable("$step\n", ProcessOutputTypes.SYSTEM)
            }
            if (stopped) return

            val java = ConnectIqSdkService.getInstance().java().toString()
            val handler = OSProcessHandler(MonkeyDo.commandLine(prepared, java, options))
            running = handler

            handler.addProcessListener(
                object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) =
                        notifyTextAvailable(event.text, outputType)

                    override fun processTerminated(event: ProcessEvent) =
                        notifyProcessTerminated(event.exitCode)
                },
            )

            notifyTextAvailable("\nRunning on ${prepared.device}...\n\n", ProcessOutputTypes.SYSTEM)
            handler.startNotify()
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
