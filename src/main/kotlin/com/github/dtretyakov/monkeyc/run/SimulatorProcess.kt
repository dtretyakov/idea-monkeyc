package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The simulators this IDE started, and what they had to say.
 *
 * A simulator started with `open -a` is nobody's child: LaunchServices takes the bundle and returns,
 * so there is no handle to wait on, nothing to read, and stopping it means walking every process on
 * the machine looking for one whose command line starts with the right `bin`. Running the program
 * inside the bundle instead makes it an ordinary child process, and all three of those problems go
 * away at once.
 *
 * Application level rather than project level, because the simulator is: it listens on 1234-1238,
 * there is one of it per machine, and two projects that each believed they had their own would be
 * two projects fighting over one window.
 *
 * What it deliberately does not do is claim to know about every simulator. One started by the SDK
 * Manager, by Garmin's own `connectiq` script, or by a previous IDE session is not in here and
 * never will be — [Simulator] still finds those by looking, and both kinds have to keep working.
 */
@Service(Service.Level.APP)
class SimulatorProcess : Disposable {

    /** One simulator we started: the handle to kill it with, and the tail of what it printed. */
    private class Started(val handle: OSProcessHandler, val log: Tail)

    /**
     * The last few kilobytes the simulator printed.
     *
     * Bounded because this runs for the life of the IDE and the simulator is chatty about ANT and
     * BLE: an unbounded buffer here is a leak measured in days. The tail is the part worth having —
     * whatever it said just before it stopped working.
     */
    private class Tail {
        private val text = StringBuilder()

        @Synchronized
        fun append(line: String) {
            text.append(line)
            if (text.length > LIMIT) text.delete(0, text.length - LIMIT)
        }

        @Synchronized
        override fun toString(): String = text.toString()
    }

    private val started = ConcurrentHashMap<Path, Started>()

    /**
     * Starts the simulator and returns once the process exists — not once it is ready.
     *
     * Readiness is a port answering, which is [Simulator]'s question and is asked there; this only
     * has to get the process up and keep hold of it. Returns false when it could not be launched at
     * all, which is a different failure from one that starts and never listens, and reads
     * differently to the user.
     */
    fun start(sdk: ConnectIqSdk): Boolean {
        val command = GeneralCommandLine(sdk.simulatorExecutable.toString())
            .withWorkingDirectory(sdk.simulatorExecutable.parent)

        return runCatching {
            val handle = OSProcessHandler(command)
            val log = Tail()
            handle.addProcessListener(
                object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        log.append(event.text)
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        // Dropped rather than kept: a dead handle would make `owns` lie, and the
                        // next start would think there was already a simulator of ours running.
                        started.remove(sdk.root)
                        LOG.info("The Connect IQ simulator at ${sdk.root} exited with ${event.exitCode}")
                    }
                },
            )
            handle.startNotify()
            started[sdk.root] = Started(handle, log)
            true
        }.getOrElse {
            LOG.warn("Could not start the Connect IQ simulator at ${sdk.simulatorExecutable}", it)
            false
        }
    }

    /** Whether the simulator listening for this SDK is one we started. */
    fun owns(sdk: ConnectIqSdk): Boolean = started[sdk.root]?.handle?.isProcessTerminated == false

    /**
     * Kills the simulator we started, and says whether there was one.
     *
     * False means "not ours", not "failed" — the caller then falls back to finding it by looking,
     * which is the only thing that works for a simulator somebody else started.
     */
    fun stop(sdk: ConnectIqSdk, timeoutMillis: Long = 10_000): Boolean {
        val ours = started.remove(sdk.root) ?: return false
        ours.handle.destroyProcess()
        ours.handle.waitFor(timeoutMillis)
        return true
    }

    /**
     * What the simulator has printed, for the SDK given or for all of them.
     *
     * Read when something has gone wrong and the plugin is about to say so. Not shown otherwise:
     * the simulator narrates its ANT and BLE stack continuously, and a console that fills with that
     * on every run is a console nobody reads when it finally matters.
     */
    fun log(sdk: ConnectIqSdk): String = started[sdk.root]?.log?.toString().orEmpty()

    /** The last [lines] lines of it, which is what fits in a notification or beside an error. */
    fun tail(sdk: ConnectIqSdk, lines: Int = 12): String =
        log(sdk).lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines).joinToString("\n")

    /**
     * Closes what we started when the IDE goes.
     *
     * A simulator whose parent has exited is reparented and stays on the screen for the rest of the
     * session, holding the port that the next IDE window will want.
     */
    override fun dispose() {
        started.keys.toList().forEach { root ->
            started.remove(root)?.handle?.destroyProcess()
        }
    }

    companion object {
        private val LOG = logger<SimulatorProcess>()

        /** Enough to explain a crash, small enough to forget about. */
        private const val LIMIT = 16 * 1024

        /**
         * Outside an IDE there is no service container, and one is not needed.
         *
         * The live tests drive a real simulator from a plain JVM, and they exercise the same start
         * and stop the IDE uses — which is the whole point of having them. Falling back to a plain
         * instance keeps that one code path instead of leaving the tested one and the shipped one
         * to drift.
         */
        private val standalone by lazy { SimulatorProcess() }

        fun getInstance(): SimulatorProcess =
            ApplicationManager.getApplication()?.getService(SimulatorProcess::class.java) ?: standalone
    }
}
