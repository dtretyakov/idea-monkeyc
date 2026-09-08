package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import java.net.InetSocketAddress
import java.net.Socket

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
     * The simulator processes belonging to this SDK.
     *
     * Matched by path rather than by name, so a simulator from another installed SDK — which the
     * SDK Manager makes easy to have — is left alone.
     */
    fun running(sdk: ConnectIqSdk): List<ProcessHandle> {
        val inside = sdk.root.resolve("bin").toString()
        return ProcessHandle.allProcesses()
            .filter { it.info().command().orElse("").startsWith(inside) }
            .toList()
    }

    /**
     * Closes the simulator, politely and then not.
     *
     * Worth having as a command of its own: the simulator holds the ports the next run needs, and
     * it gets into states — a hung app, a device left half-loaded — that only a restart clears.
     */
    fun stop(sdk: ConnectIqSdk, timeoutMillis: Long = 10_000): Boolean {
        val processes = running(sdk)
        if (processes.isEmpty()) return true

        processes.forEach { it.destroy() }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (processes.none { it.isAlive }) return true
            Thread.sleep(POLL_MILLIS)
        }

        processes.filter { it.isAlive }.forEach { it.destroyForcibly() }
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
    private const val CONNECT_TIMEOUT_MILLIS = 200
}
