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
            val reason = e.message ?: "The run stopped with ${e.javaClass.simpleName} and no message."
            notifyTextAvailable("\n$reason\n", ProcessOutputTypes.STDERR)
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

        // The simulator leaks two pipes per run and stops accepting connections after a few dozen
        // of them — acknowledged by Garmin in 2023 and still open. The observed behaviour of the
        // VS Code extension is that the first attempt fails and the second succeeds, and it does
        // not restart the simulator to get there. So: push again, and only then reach for the
        // bigger hammer. Driving `monkeydo` from the command line is what puts us on this side of
        // the bug, and a run that quietly works the second time is the whole difference between
        // "flaky plugin" and "no, that is the SDK".
        repeat(ATTEMPTS) { index ->
            if (stopped) return
            val attempt = pushToSimulator(prepared, java)
            if (stopped) return

            val last = index == ATTEMPTS - 1
            if (!attempt.simulatorRefused || last) {
                finish(attempt.exitCode)
                return
            }
            recover(prepared, attemptsSoFar = index + 1)
        }
    }

    /** What one `monkeydo` invocation did, once it is over. */
    private class Attempt(val exitCode: Int, val simulatorRefused: Boolean)

    /**
     * Runs `monkeydo` once and waits for it, forwarding everything it says as it says it.
     *
     * Output is not held back while we decide whether to retry. It could be — the refusal is
     * immediate and produces nothing else — but a run that prints "Unable to connect to
     * simulator." and then recovers has told the user something true, and a silent retry would
     * leave them wondering why a run took twice as long.
     */
    private fun pushToSimulator(prepared: PreparedLaunch, java: String): Attempt {
        val handler = OSProcessHandler(MonkeyDo.commandLine(prepared, java, options))
        running = handler
        val errors = StringBuilder()

        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    // The platform's own handler announces itself: BaseOSProcessHandler.startNotify
                    // writes the whole command line as SYSTEM output. Forwarding that puts a
                    // five-hundred-character java invocation above the app's first line of output,
                    // which is a log entry rather than anything the user asked to see. Our own
                    // progress lines are written directly and are unaffected.
                    if (outputType === ProcessOutputTypes.SYSTEM) return

                    if (outputType === ProcessOutputTypes.STDERR) errors.append(event.text)

                    if (testMessages != null && outputType === ProcessOutputTypes.STDOUT) {
                        val translated = testMessages.translate(event.text)
                        if (translated.isNotEmpty()) notifyTextAvailable(translated, outputType)
                    } else {
                        notifyTextAvailable(event.text, outputType)
                    }
                }
            },
        )

        notifyTextAvailable("\nRunning on ${prepared.device}...\n\n", ProcessOutputTypes.SYSTEM)
        handler.startNotify()
        handler.waitFor()

        // `exitCode` is null only for a process that is still running, which this one is not.
        val exitCode = handler.exitCode ?: 1
        return Attempt(exitCode, MonkeyDo.simulatorRefused(exitCode, errors.toString()))
    }

    /**
     * Gets the simulator back into a state where the next push can work.
     *
     * The first refusal is answered by simply trying again, which is what the SDK's own bug report
     * says is enough. A second one means the simulator is properly wedged, and only a restart
     * clears that.
     */
    private fun recover(prepared: PreparedLaunch, attemptsSoFar: Int) {
        if (attemptsSoFar == 1) {
            report("The simulator refused the app. Pushing it again.")
            return
        }
        report("The simulator refused the app twice. Restarting it.")
        if (!Simulator.restart(prepared.sdk)) {
            report("The simulator did not come back. The next attempt is likely to fail too.")
        }
    }

    /** Closes the test tree, if there is one, and ends the run. */
    private fun finish(exitCode: Int) {
        // A test still open here never reported a result, and the tree would show it running for
        // ever; this is the last chance to close it.
        testMessages?.flush()
            ?.takeIf { it.isNotEmpty() }
            ?.let { notifyTextAvailable(it, ProcessOutputTypes.STDOUT) }
        notifyProcessTerminated(exitCode)
    }

    private fun report(step: String) = notifyTextAvailable("$step\n", ProcessOutputTypes.SYSTEM)

    override fun destroyProcessImpl() {
        stopped = true
        val handler = running
        if (handler == null) {
            // Stopped during the build. The compiler is not ours to kill — it runs under the Build
            // tool window — so it finishes on its own; `stopped` only keeps the app from being
            // pushed to the simulator afterwards. Said out loud, because a Run window that goes
            // red while the Build window keeps working otherwise looks like two contradictions.
            notifyTextAvailable(
                "\nStopped. The compiler was already running and finishes in the Build window; " +
                    "nothing will be started when it does.\n",
                ProcessOutputTypes.SYSTEM,
            )
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

        /**
         * How many times to push the app before giving up: try, try again, restart and try once
         * more. Three is what the failure needs and no more — a wedged simulator that survives a
         * restart is a different problem, and looping on it would only hide it.
         */
        const val ATTEMPTS = 3
    }
}
