package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.run.test.MonkeyCTestMessages
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
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
     * What was pushed, so Stop can close it.
     *
     * Killing `monkeydo` stops the program that pushed the app and relays its output, and leaves
     * the app itself running in the simulator — which the next Debug then cannot get past. Kept
     * here because by the time Stop arrives the launch is over and there is nothing else holding
     * the sdk and the id.
     */
    @Volatile
    private var pushed: PreparedLaunch? = null

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
            if (options.kind.launches && !MonkeyCLaunch.onWatch(project, options)) {
                runInSimulator()
            } else {
                buildOnly()
            }
            // Whatever the branch above did, the run has to be over when it returns. Both branches
            // end it themselves and say so in their own words; this is the backstop, because the
            // failure it prevents is silent and permanent — a Run window holding a live process
            // with a spinning Stop button for the rest of the session, no error, nothing to click.
            finish(STOPPED)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            // Everything, not only ExecutionException: a build can fail on a read-only directory
            // or a full disk, and an escaping exception would leave the Run window showing a live
            // process with an empty console and no way to see why.
            val reason = e.message ?: "The run stopped with ${e.javaClass.simpleName} and no message."
            notifyTextAvailable("\n$reason\n", ProcessOutputTypes.STDERR)
            if (e !is ExecutionException) LOG.warn("Could not run the Connect IQ app", e)
            finish(1)
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
        if (MonkeyCLaunch.onWatch(project, options)) offerToInstall(built)
        finish(0)
    }

    /**
     * Says where the build can go, and offers to put it there when a watch is plugged in.
     *
     * A build for the device exists to end up on a device, and until now the console said so and
     * left the four clerical steps — find the volume, find `GARMIN/APPS`, copy, unplug — to the
     * developer. Offered rather than done: it writes to hardware the user owns, and the moment
     * they connected it is not necessarily the moment they meant to install anything.
     */
    private fun offerToInstall(built: BuiltArtifact) {
        val targets = GarminTarget.attached()
        if (targets.isEmpty()) {
            notifyTextAvailable(
                "Copy it to GARMIN/APPS on the watch over USB to install it.\n",
                ProcessOutputTypes.SYSTEM,
            )
            // Only said when there is no device: a current watch speaks MTP and appears under no
            // volume, so "no device" and "no tool to see it with" look identical from here, and
            // the second is fixable. Not said when a watch was found, because then nothing is
            // missing.
            if (GarminTarget.mtpToolMissing()) {
                notifyTextAvailable("\n${MtpLocator.INSTALL_HINT}\n", ProcessOutputTypes.SYSTEM)
            }
            return
        }

        notifyTextAvailable("Connected: ${targets.joinToString { it.name }}.\n", ProcessOutputTypes.SYSTEM)
        MonkeyCInstallNotice.offer(project, built, targets)
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
            if (stopped) return finish(STOPPED)
            val attempt = pushToSimulator(prepared, java)
            // Stop is the one exit that has to be spelled out. The process is killed by
            // destroyProcessImpl, which cannot end the run itself — the run is this loop, and it
            // may have another attempt to make — so the ending happens here, and leaving it out
            // is a Run window with a live process in it for the rest of the session.
            if (stopped) return finish(attempt.exitCode)

            val last = index == ATTEMPTS - 1
            if (!attempt.simulatorRefused || last) return finish(attempt.exitCode)

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
        // A close from the previous Stop may still be on its way. It names the same app, so
        // overtaking it means pushing an app and having it closed a second later by a message
        // meant for its predecessor.
        SimulatorApp.awaitClose()

        val handler = OSProcessHandler(MonkeyDo.commandLine(prepared, java, options))
        running = handler
        pushed = prepared
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

    /**
     * Closes the test tree, if there is one, and ends the run.
     *
     * Safe to call twice. Stopping before anything was launched ends the run inside
     * [destroyProcessImpl], because there is no process there to end it, and the loop then reaches
     * here as well — and a second `notifyProcessTerminated` is an error in the platform's log.
     * Terminating while already terminating is not that: it is the normal end of a stopped run.
     */
    private fun finish(exitCode: Int) {
        // Only `isProcessTerminated`. Guarding on `isProcessTerminating` as well looks like the
        // same thing and is the opposite: the platform sets that state the moment Stop is pressed,
        // before it calls `destroyProcessImpl`, so the guard fired in exactly the case this has to
        // run — and the handler stayed TERMINATING for ever while the IDE showed "Waiting for
        // process detach" and refused to close the project.
        if (isProcessTerminated) return
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
            finish(1)
        } else {
            handler.destroyProcess()
            closeAppInSimulator()
        }
    }

    /**
     * Tells the simulator to close the app, which killing `monkeydo` does not.
     *
     * On a pooled thread and not waited for: Stop has to feel immediate, and the run is over either
     * way. Only for a simulator run — a build for the watch never pushed anything, and a test run
     * ends by itself.
     */
    private fun closeAppInSimulator() {
        val prepared = pushed ?: return
        val id = runReadAction { MonkeyCProject.getInstance(project).manifest(prepared.root)?.applicationId }
            ?.takeIf { it.isNotBlank() } ?: return

        SimulatorApp.closeLater(prepared.sdk, id)
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

        /** What a run that the user stopped exits with, when nothing else has an answer. */
        const val STOPPED = 1
    }
}
