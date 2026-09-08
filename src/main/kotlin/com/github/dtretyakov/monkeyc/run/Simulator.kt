package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path

/**
 * Starts the Connect IQ simulator and waits until it will accept an app.
 *
 * The simulator is a GUI application, and the thing that pushes an app into it — `monkeydo`, or the
 * debug adapter — talks to a debug shell the simulator opens on a TCP port. Starting the process
 * and being able to use it are therefore two different moments, and the second one is the one to
 * wait for.
 */
object Simulator {

    /**
     * The ports the simulator's shell listens on. It takes the first free one, so several
     * simulators can run at once; there is no way to ask which it took.
     */
    private val PORTS = 1234..1238

    fun isReady(): Boolean = PORTS.any { canConnect(it) }

    /**
     * Every running Connect IQ simulator, from whichever SDK started it.
     *
     * Deliberately not scoped to the current SDK. Scoping sounded principled — leave another
     * SDK's simulator alone — and broke the case that actually happens: the SDK Manager installs
     * a new SDK and makes it current while yesterday's simulator is still open. Stop then found
     * nothing and said so while the window sat there, and Start saw the port taken and did
     * nothing. Two SDKs deliberately running side by side is far rarer than upgrading one.
     */
    fun running(dataRoot: Path = ConnectIqSdk.dataRoot()): List<ProcessHandle> {
        val prefixes = simulatorPrefixes(dataRoot)
        return ProcessHandle.allProcesses()
            .filter { handle -> handle.simulatorRoot(prefixes) != null }
            .toList()
    }

    /**
     * The SDK whose simulator is listening now, or null when none is.
     *
     * Needed because "a simulator is up" is not the same question as "the right simulator is up",
     * and a `.prg` built by one SDK pushed into another's simulator fails in ways that read as a
     * broken plugin.
     */
    fun runningSdk(dataRoot: Path = ConnectIqSdk.dataRoot()): Path? {
        val prefixes = simulatorPrefixes(dataRoot)
        return ProcessHandle.allProcesses()
            .map { it.simulatorRoot(prefixes) }
            .filter { it != null }
            .findFirst()
            .orElse(null)
    }

    /** An SDK other than [sdk] whose simulator holds the port, or null when there is no conflict. */
    fun conflictingSdk(sdk: ConnectIqSdk): Path? {
        if (!isReady()) return null
        val live = runningSdk(sdk.dataRoot) ?: return null
        return live.takeIf { !it.sameAs(sdk.root) }
    }

    /** Every unpacked SDK's `bin`, in both spellings, since a process reports where it really started. */
    private fun simulatorPrefixes(dataRoot: Path): List<String> =
        (ConnectIqSdk.installed(dataRoot) + listOfNotNull(ConnectIqSdk.detect(dataRoot)?.root))
            .distinct()
            .flatMap { root ->
                val bin = root.resolve("bin")
                listOfNotNull(bin.toString(), runCatching { bin.toRealPath().toString() }.getOrNull())
            }
            .distinct()

    /** The SDK root this process was started from, when it is a Connect IQ simulator. */
    private fun ProcessHandle.simulatorRoot(prefixes: List<String>): Path? {
        val command = info().command().orElse("").takeIf { it.isNotEmpty() } ?: return null
        val prefix = prefixes.firstOrNull { command.startsWith(it) } ?: return null
        return Path.of(prefix).parent
    }

    private fun Path.sameAs(other: Path): Boolean =
        runCatching { toRealPath() == other.toRealPath() }.getOrDefault(this == other)

    /**
     * Closes the simulator, politely and then not.
     *
     * Worth having as a command of its own: the simulator holds the ports the next run needs, and
     * it gets into states — a hung app, a device left half-loaded — that only a restart clears.
     */
    fun stop(sdk: ConnectIqSdk, timeoutMillis: Long = 10_000): Boolean {
        val processes = running(sdk.dataRoot)
        if (processes.isEmpty()) return true

        processes.forEach { it.destroy() }
        if (awaitGone(processes, timeoutMillis)) return true

        // Asking harder, and then waiting again: destroyForcibly only delivers the signal, so
        // reading isAlive straight afterwards reports "still running" for a process that is about
        // to be gone — and the user would be told the simulator did not stop when it did.
        processes.filter { it.isAlive }.forEach { it.destroyForcibly() }
        return awaitGone(processes, FORCED_TIMEOUT_MILLIS)
    }

    private fun awaitGone(processes: List<ProcessHandle>, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (processes.none { it.isAlive }) return true
            Thread.sleep(POLL_MILLIS)
        }
        return processes.none { it.isAlive }
    }

    /** Stops it and brings it back, which is the whole point of being able to stop it. */
    fun restart(sdk: ConnectIqSdk): Boolean {
        stop(sdk)
        // The port stays bound for a moment after the process goes, and start() would see that as
        // a simulator already running and do nothing.
        awaitClosed()
        return start(sdk)
    }

    private fun awaitClosed(timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && isReady()) {
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Starts the simulator if it is not already up, and returns once it answers. */
    fun start(sdk: ConnectIqSdk, timeoutMillis: Long = 40_000): Boolean {
        if (isReady()) return true

        val command = if (System.getProperty("os.name").startsWith("Mac")) {
            // The bundle has to be opened, not executed: run the inner binary directly and macOS
            // gives it no window server connection.
            GeneralCommandLine("open", "-a", sdk.simulator.toString())
        } else {
            GeneralCommandLine(sdk.simulator.toString())
                .withWorkingDirectory(sdk.simulator.parent)
        }

        // Nothing consumes the output; the simulator's own window is where it reports.
        OSProcessHandler(command).startNotify()

        return await(timeoutMillis)
    }

    private fun await(timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (isReady()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun canConnect(port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS)
                true
            }
        }.getOrDefault(false)

    private const val POLL_MILLIS = 200L

    /** How long a process gets after SIGKILL before the answer is "it is still there". */
    private const val FORCED_TIMEOUT_MILLIS = 3_000L
    private const val CONNECT_TIMEOUT_MILLIS = 200
}
