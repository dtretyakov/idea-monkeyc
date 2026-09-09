package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.util.io.BaseOutputReader
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
        // One we already have. Overwriting it would drop the handle while the process runs on,
        // and `dispose` would then leave it holding the port after the IDE has gone — which is
        // reachable, because a simulator can be alive and no longer accepting connections, and
        // that is exactly when a caller tries to start another.
        started[sdk.root]?.takeIf { !it.handle.isProcessTerminated }?.let {
            LOG.info("A simulator for ${sdk.root} is already running as our child; not starting another")
            return true
        }

        val command = GeneralCommandLine(sdk.simulatorExecutable.toString())
            .withWorkingDirectory(sdk.simulatorExecutable.parent)

        return runCatching {
            // A GUI application that says almost nothing: the platform's default reader polls it
            // and then warns in the log that it has seen no output for a long time, on every run.
            // The warning names its own remedy, which is this.
            val handle = object : OSProcessHandler(command) {
                override fun readerOptions(): BaseOutputReader.Options =
                    BaseOutputReader.Options.forMostlySilentProcess()
            }
            val log = Tail()
            val entry = Started(handle, log)
            handle.addProcessListener(
                object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        log.append(event.text)
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        // By identity, so a listener firing late cannot evict the entry of a
                        // simulator started after it.
                        started.remove(sdk.root, entry)
                        LOG.info("The Connect IQ simulator at ${sdk.root} exited with ${event.exitCode}")
                    }
                },
            )
            // Recorded before it is started. A program that exits at once — a half-downloaded SDK
            // does that — otherwise fires `processTerminated` against a map that has no entry yet,
            // and the dead handle is then written in behind it, leaving `log` handing a later
            // failure the output of a different process.
            started[sdk.root] = entry
            handle.startNotify()
            true
        }.getOrElse {
            started.remove(sdk.root)
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
            val ours = started.remove(root) ?: return@forEach
            ours.handle.destroyProcess()
            // Waited for, because a child is not killed by the JVM exiting on Unix: signal it and
            // return, and the simulator outlives the IDE holding the port the next window wants.
            // Briefly, because shutdown is not the place to hang on a window that will not close.
            ours.handle.waitFor(SHUTDOWN_MILLIS)
        }
    }

    companion object {
        private val LOG = logger<SimulatorProcess>()

        /** Enough to explain a crash, small enough to forget about. */
        private const val LIMIT = 16 * 1024

        /** How long shutdown waits for the simulator to go before letting the IDE finish. */
        private const val SHUTDOWN_MILLIS = 3_000L

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
