package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
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

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread { launch() }
    }

    private fun launch() {
        try {
            val prepared = MonkeyCLaunch.prepare(project, options) { step ->
                notifyTextAvailable("$step\n", ProcessOutputTypes.SYSTEM)
            }

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
        } catch (e: ExecutionException) {
            notifyTextAvailable("\n${e.message}\n", ProcessOutputTypes.STDERR)
            notifyProcessTerminated(1)
        }
    }

    override fun destroyProcessImpl() {
        val handler = running
        if (handler == null) {
            // Killed during the build; the compiler will finish on its own and be ignored.
            notifyProcessTerminated(1)
        } else {
            handler.destroyProcess()
        }
    }

    override fun detachProcessImpl() {
        running?.detachProcess() ?: notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = running?.processInput
}
