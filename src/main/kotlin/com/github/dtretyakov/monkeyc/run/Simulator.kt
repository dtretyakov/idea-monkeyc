package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.diagnostic.logger
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

    /**
     * Something answers on one of the simulator's ports.
     *
     * This is what the waiting loops need, and it is deliberately not the same question as "the
     * simulator is up": 1234 is a popular port and the answer can come from something else
     * entirely. [strangerHoldsPort] is the question with the suspicion in it.
     */
    fun isReady(): Boolean = PORTS.any { canConnect(it) }

    /**
     * The port answers, and no Connect IQ simulator can be found to explain it.
     *
     * Worth asking before a run rather than after. Whatever holds the port will not speak the
     * debug shell's protocol, so `monkeydo` fails somewhere inside a handshake and reports it as
     * the app failing to launch — which is a long way from the truth and sent one developer on
     * the forums through a fourteen-reply thread before finding that Hyper-V had reserved the
     * range. Guessing wrong here is cheap: the caller warns, it does not refuse.
     */
    fun strangerHoldsPort(sdk: ConnectIqSdk): Boolean =
        isReady() && running(sdk.dataRoot, sdk.root).isEmpty()

    /**
     * Every running Connect IQ simulator, from whichever SDK started it.
     *
     * Deliberately not scoped to the current SDK. Scoping sounded principled — leave another
     * SDK's simulator alone — and broke the case that actually happens: the SDK Manager installs
     * a new SDK and makes it current while yesterday's simulator is still open. Stop then found
     * nothing and said so while the window sat there, and Start saw the port taken and did
     * nothing. Two SDKs deliberately running side by side is far rarer than upgrading one.
     */
    fun running(dataRoot: Path = ConnectIqSdk.dataRoot(), vararg also: Path): List<ProcessHandle> {
        val prefixes = simulatorPrefixes(dataRoot, also.toList())
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
    fun runningSdk(dataRoot: Path = ConnectIqSdk.dataRoot(), vararg also: Path): Path? {
        val prefixes = simulatorPrefixes(dataRoot, also.toList())
        return ProcessHandle.allProcesses()
            .map { it.simulatorRoot(prefixes) }
            .filter { it != null }
            .findFirst()
            .orElse(null)
    }

    /** An SDK other than [sdk] whose simulator holds the port, or null when there is no conflict. */
    fun conflictingSdk(sdk: ConnectIqSdk): Path? {
        if (!isReady()) return null
        val live = runningSdk(sdk.dataRoot, sdk.root) ?: return null
        return live.takeIf { !it.sameAs(sdk.root) }
    }

    /**
     * Every unpacked SDK's `bin`, in both spellings, since a process reports where it really
     * started.
     *
     * [also] is how an SDK that is none of the SDK Manager's business gets in: a project can pin
     * one, and the settings can point at one, and neither is under `Sdks` or named by
     * `current-sdk.cfg`. Without it a simulator started from that SDK matches nothing, and the
     * caller concludes a stranger holds the port while the user's own simulator is on the screen.
     */
    private fun simulatorPrefixes(dataRoot: Path, also: List<Path> = emptyList()): List<String> =
        (ConnectIqSdk.installed(dataRoot) + listOfNotNull(ConnectIqSdk.detect(dataRoot)?.root) + also)
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
        // Ours first, because for it there is a handle and no guessing. The search below is for a
        // simulator somebody else started, which is a perfectly ordinary thing to find.
        if (SimulatorProcess.getInstance().stop(sdk, timeoutMillis)) {
            awaitClosed()
            if (running(sdk.dataRoot).isEmpty()) return true
        }

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

    /**
     * Starts the simulator if it is not already up, and returns once it answers.
     *
     * The program inside the bundle is executed directly rather than handed to `open`. This used to
     * say the opposite — that running the inner binary leaves macOS refusing it a window server
     * connection — and that is not what happens: it opens its window, listens on the debug port,
     * accepts an app from `monkeydo`, runs the unit tests, and does not take the focus away from
     * the IDE while doing it. What `open` costs is everything a child process gives: there is no
     * handle to wait on, nothing to read, and stopping it means searching the machine for it.
     *
     * `open` remains the fallback rather than the default. If some SDK build turns out to need it,
     * the simulator still starts — a slower path with less information, not a broken plugin.
     */
    fun start(sdk: ConnectIqSdk, timeoutMillis: Long = 40_000): Boolean {
        if (isReady()) return true

        if (SimulatorProcess.getInstance().start(sdk) && await(timeoutMillis)) return true

        // Either it could not be launched at all, or it launched and never listened. Both are worth
        // one attempt through LaunchServices before giving up on the user's behalf.
        if (isReady()) return true
        LOG.info("The simulator did not come up when executed directly; falling back to `open`.")
        runCatching { OSProcessHandler(openCommand(sdk)).startNotify() }
            .onFailure { LOG.warn("Could not open ${sdk.simulator}", it) }

        return await(timeoutMillis)
    }

    /** How the simulator was started before it was a child process, kept as the fallback. */
    private fun openCommand(sdk: ConnectIqSdk): GeneralCommandLine =
        if (System.getProperty("os.name").startsWith("Mac")) {
            // `-g` so the fallback does not do what the direct launch avoids: steal the focus.
            GeneralCommandLine("open", "-g", "-a", sdk.simulator.toString())
        } else {
            GeneralCommandLine(sdk.simulatorExecutable.toString())
                .withWorkingDirectory(sdk.simulatorExecutable.parent)
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

    private val LOG = logger<Simulator>()

    private const val POLL_MILLIS = 200L

    /** How long a process gets after SIGKILL before the answer is "it is still there". */
    private const val FORCED_TIMEOUT_MILLIS = 3_000L
    private const val CONNECT_TIMEOUT_MILLIS = 200
}
