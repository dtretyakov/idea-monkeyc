package com.github.dtretyakov.monkeyc.run.session

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import com.intellij.execution.ExecutionException
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

/**
 * The plugin's one connection to the running simulator, and the one app on it.
 *
 * The simulator's debug channel takes a single client: a second connection disconnects the first.
 * That is the whole reason this exists. `monkeydo` is a client, one per run, so the plugin could
 * never hold a connection of its own alongside it — and having no connection is what made Stop
 * unable to stop anything. It killed `monkeydo`; the app went on running in the simulator with
 * nothing attached, and the next debug session met it there as a timeout.
 *
 * So: one connection for the IDE, held open across runs, with at most one app on it. Starting a
 * run closes whatever the last one left behind, which is what makes Run, Stop, Run the same thing
 * twice rather than a thing and then a mystery.
 *
 * The connection itself lives in [SessionHelper], in a JVM of its own — see that class for why.
 * This service starts it, speaks its line protocol, and turns what comes back into a run.
 */
@Service(Service.Level.APP)
class SimulatorSession : Disposable {

    /**
     * The connection could not be had — which is a reason to fall back, not to fail a run.
     *
     * Separate from [ExecutionException] for exactly that: everything the user did was fine, and
     * `monkeydo` can still run their app.
     */
    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Guards the fields below, and nothing slow: Stop takes it too, and must never wait. */
    private val lock = ReentrantLock()

    /**
     * Held from the moment a run starts closing its predecessor until its own app is up.
     *
     * Everything in that stretch is answered by a message that says nothing about which run asked
     * for it — `pushed`, `deviceStarted` — so two runs launching at once would each be able to
     * take the other's answer for their own. One at a time removes the question. It is not held
     * for the run itself, which is most of the time and would make Run mean "wait for the other
     * app to be closed by hand".
     */
    private val launching = ReentrantLock()

    /**
     * Guards opening a connection, so two runs never start two helpers at once.
     *
     * The simulator's channel takes one client, and a second one disconnects the first — so a race
     * here would not produce a spare connection to throw away, it would produce two that break
     * each other.
     */
    private val connecting = ReentrantLock()

    private var connection: Connection? = null

    /** The app on the connection, so the next run can close it before starting its own. */
    private var live: LiveRun? = null

    /**
     * An app on the connection, and whether anyone is still willing to wait for it.
     *
     * Stop is the only thing here with a deadline. A run waits for its app for as long as the app
     * wants to live, because that is what running an app is; but once Stop has asked it to close,
     * a simulator that never confirms it must not hold the Run window open for the rest of the
     * session.
     */
    private class LiveRun(val app: String, private val stopped: () -> Boolean) {
        @Volatile
        var closeAskedAt = 0L

        /**
         * The connection the app was launched on.
         *
         * A run that comes later closes the app before it, but only if it is looking at the same
         * connection: one that has been replaced never ran anything, and closing an app on it
         * would be waiting for an answer nobody is going to give.
         */
        @Volatile
        var ranOn: Connection? = null

        /**
         * Whether Stop has been pressed, which is reason enough to abandon a launch.
         *
         * Asked of the caller as well as of this run, because Stop can be pressed in the moment
         * between the caller deciding to run and this run existing to be stopped — and a Stop that
         * lands in that gap would otherwise be ignored and the app started anyway.
         */
        val asked: Boolean get() = closeAskedAt != 0L || stopped()

        /** Whether Stop has been pressed and the simulator has had long enough to answer it. */
        fun givenUp(): Boolean =
            asked && System.currentTimeMillis() - closeAskedAt > CLOSE_TIMEOUT
    }

    /**
     * Runs an app, and returns when it ends.
     *
     * Blocking, on the caller's thread, because that is the shape the Run window wants: the
     * process handler is already off the EDT, and its whole job is to be alive for as long as the
     * app is.
     *
     * @param tests null to run the app, an empty list to run all of its tests, or the tests to run
     * @param stopped whether the caller has been asked to stop, which abandons a launch in progress
     */
    fun run(
        sdk: ConnectIqSdk,
        prg: Path,
        device: String,
        uuid: String,
        tests: List<String>?,
        nativePairing: Boolean,
        stopped: () -> Boolean,
        report: (String) -> Unit,
        output: (String) -> Unit,
    ): Int {
        val app = dashed(uuid)
        val mine = LiveRun(app, stopped)
        // On record before anything can block, so that a Stop pressed while the helper is still
        // starting has something to stop rather than nothing to find.
        val previous = lock.withLock { live.also { live = mine } }

        try {
            val connection = connect(sdk)
            mine.ranOn = connection

            connection.listening().use { channel ->
                launching.withLock {
                    // The invariant, enforced where it can be: one app on this connection.
                    // Whatever the last run left behind goes first, so that a Run after a Stop
                    // starts from the same place a first Run does. Waited for, because an app that
                    // is still closing is exactly what the next launch would trip over.
                    if (previous != null && previous.ranOn === connection) {
                        connection.send("close ${previous.app}")
                        val gone = channel.until(CLOSE_TIMEOUT, output, mine::asked) {
                            it == "event appTerminated ${previous.app}"
                        }
                        if (gone == null && !mine.asked) {
                            report("The app from the last run has not closed yet; starting anyway.")
                        }
                    }

                    if (mine.asked) return FAILED
                    connection.send("push $prg")
                    channel.expect("pushed", PUSH_TIMEOUT, output, mine::asked)

                    // Every run, even when the simulator already has this device loaded. Skipping
                    // it when the device looks unchanged is the obvious saving and it is wrong:
                    // loading a device is also what clears whatever the last app left on it, and
                    // without that a second run — a test run followed by an ordinary one, say —
                    // gets as far as `appLaunching` and stops there for ever.
                    if (mine.asked) return FAILED
                    report("Loading $device in the simulator...")
                    connection.send("device $device")
                    channel.expect("event deviceStarted $device", DEVICE_TIMEOUT, output, mine::asked)

                    if (mine.asked) return FAILED
                    connection.send(
                        when {
                            tests != null -> "tests $uuid ${tests.joinToString(" ")}".trim()
                            nativePairing -> "open $uuid native"
                            else -> "open $uuid"
                        },
                    )
                    channel.expect("event appStarted $app", START_TIMEOUT, output, mine::asked)
                }
                if (mine.asked) return FAILED

                report("Running on $device...\n")

                // From here the run belongs to the app: it ends when the app does, or when Stop
                // asks it to.
                //
                // A connection lost from here on ends the run rather than escaping: the app has
                // been started, and letting [Unavailable] out would send the caller to `monkeydo`
                // to start it a second time.
                val ended = try {
                    channel.until(FOREVER, output, mine::givenUp) { it == "event appTerminated $app" }
                } catch (e: Unavailable) {
                    report("This run is no longer being watched: ${e.message}")
                    return FAILED
                }
                if (ended == null) {
                    report("The simulator did not confirm that the app closed.")
                    return FAILED
                }
                return if (channel.crashed) FAILED else 0
            }
        } finally {
            lock.withLock { if (live === mine) live = null }
        }
    }

    /**
     * Asks the running app to close.
     *
     * Only asks: the run's own thread is watching the connection and will see the app terminate,
     * which is what ends the run. Waiting here as well would put two readers on one connection,
     * and the answer would go to whichever of them happened to read it.
     */
    fun stop() {
        val (connection, run) = lock.withLock { connection to live }
        if (run == null) return
        // Recorded even when there is nothing to send it to: a run still waiting for a helper to
        // start reads this and abandons the launch rather than opening an app nobody wants.
        run.closeAskedAt = System.currentTimeMillis()
        if (connection == null) return
        try {
            connection.send("close ${run.app}")
        } catch (e: Unavailable) {
            LOG.info("The simulator connection went away before the app could be closed", e)
        }
    }

    /**
     * Closes the app and hands the simulator back, for something else to connect to.
     *
     * The debugger is that something else. Its adapter is a separate program, and the simulator's
     * channel carries one client — so the moment the adapter connects, this connection is told
     * `shellDisconnected` and stops hearing anything at all. Measured, not assumed: the displaced
     * client goes on printing nothing while the newcomer gets every event, including the end of
     * the app the displaced one was watching.
     *
     * Taken from us that way, a run would simply never end. So it is given up on purpose instead:
     * the app closes, the run finishes in the Run window with an exit code like any other, and the
     * debugger starts on a simulator with nothing in its way.
     */
    fun release() {
        val (connection, run) = lock.withLock { connection to live }
        if (connection == null) return

        if (run != null) {
            run.closeAskedAt = System.currentTimeMillis()
            // A channel of its own: the run's own thread is still reading, and both are given
            // every line, so neither takes the other's answer.
            connection.listening().use { channel ->
                runCatching {
                    connection.send("close ${run.app}")
                    channel.until(CLOSE_TIMEOUT, output = {}) { it == "event appTerminated ${run.app}" }
                }
            }
        }

        lock.withLock {
            if (this.connection === connection) {
                this.connection = null
                live = null
            }
        }
        connection.close()
    }

    /**
     * The connection, opening one if the last is gone.
     *
     * Starting it takes as long as a JVM takes and is deliberately not done under [lock]: Stop
     * takes that lock, and a Stop that waits for a helper to finish starting is a Stop that looks
     * broken.
     */
    private fun connect(sdk: ConnectIqSdk): Connection = connecting.withLock {
        lock.withLock { connection }?.let { if (it.isAlive) return@withLock it }

        val started = Connection.start(sdk)
        lock.withLock {
            connection?.close()
            connection = started
        }
        started
    }

    override fun dispose() {
        lock.withLock {
            connection?.close()
            connection = null
        }
    }

    /**
     * The helper process, and the line protocol over its pipes.
     *
     * One thread reads the helper and hands every line to each open [Channel]; a run reads its own
     * channel. Copied to each rather than taken from one queue because two runs can overlap for as
     * long as it takes the first to close, and a shared queue would hand one run's answer to the
     * other.
     */
    private class Connection(
        private val process: Process,
        private val input: BufferedWriter,
    ) {
        private val channels = CopyOnWriteArrayList<Channel>()
        private val sending = ReentrantLock()

        /**
         * False once something else has taken the simulator's channel from us.
         *
         * The channel carries one client: a newcomer takes it and the one already there is told
         * `shellDisconnected` and then hears nothing more — not the app's output, not even the app
         * ending. So a connection that has been displaced is not a connection, and the next run
         * opens a new one rather than waiting on a pipe that will stay quiet.
         */
        @Volatile
        private var ours = true

        val isAlive: Boolean get() = process.isAlive && ours

        fun send(line: String) = sending.withLock {
            try {
                input.write(line)
                input.newLine()
                input.flush()
            } catch (e: IOException) {
                throw Unavailable("The simulator connection closed.", e)
            }
        }

        fun listening(): Channel = Channel(this).also { channels.add(it) }

        fun forget(channel: Channel) {
            channels.remove(channel)
        }

        fun close() {
            try {
                send("quit")
            } catch (ignored: Unavailable) {
                // Already gone, which is where this was heading anyway.
            }
            process.destroy()
        }

        private fun read(reader: BufferedReader) {
            try {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        if (line == DISPLACED) ours = false
                        channels.forEach { it.take(line) }
                    }
                }
            } catch (e: IOException) {
                LOG.info("The simulator connection stopped reading", e)
            } finally {
                // Everyone waiting is waiting for something that is never coming now.
                channels.forEach { it.take(GONE) }
            }
        }

        companion object {
            /**
             * Starts the helper, and does not return until it says it has a connection.
             *
             * Every reason this can fail is a reason to run the app through `monkeydo` instead, so
             * they all come back as [Unavailable] rather than as a failed run.
             */
            fun start(sdk: ConnectIqSdk): Connection {
                if (!sdk.monkeybrainsJar.exists()) {
                    throw Unavailable("${sdk.monkeybrainsJar} is missing from the SDK.")
                }
                val plugin = PathManager.getJarPathForClass(SessionHelper::class.java)
                    ?: throw Unavailable("The plugin could not find its own classes.")

                val command = listOf(
                    java().toString(),
                    "-classpath",
                    listOf(plugin, sdk.monkeybrainsJar.toString()).joinToString(File.pathSeparator),
                    SessionHelper::class.java.name,
                    sdk.shell.toString(),
                    // So that no helper outlives this IDE, however this IDE ends: a connection
                    // nobody owns any more is one that nothing else can have either.
                    ProcessHandle.current().pid().toString(),
                )
                val process = try {
                    // The helper says everything on standard output; anything on standard error is
                    // the JVM itself failing, and merging the two puts that in the same place.
                    ProcessBuilder(command).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    throw Unavailable("The simulator connection would not start.", e)
                }

                val connection = Connection(process, process.outputWriter())
                Thread({ connection.read(process.inputReader()) }, "Connect IQ simulator session")
                    .apply { isDaemon = true }
                    .start()

                connection.listening().use { channel ->
                    try {
                        // Nothing said before the connection is open belongs to any run.
                        channel.expect("ready", READY_TIMEOUT, output = {})
                    } catch (e: Exception) {
                        connection.close()
                        // The helper reports what the SDK told it, and the commonest thing it has
                        // to say — that no simulator is listening — is a fact about the simulator
                        // rather than about the helper, so it is worth passing on as it stands.
                        throw if (e is Unavailable) e else Unavailable(e.message ?: "The simulator connection did not open.", e)
                    }
                }
                return connection
            }
        }
    }

    /**
     * One reader's view of the connection.
     *
     * A run waits on its own channel rather than on a signal raised elsewhere, so that the app's
     * output stays in order with the events around it: everything printed between a command and
     * its answer reaches the console on the way past, instead of being buffered and put back.
     */
    private class Channel(private val connection: Connection) : AutoCloseable {

        private val lines = LinkedBlockingQueue<String>(BACKLOG)

        /** Whether the app running on this channel has crashed since it last started. */
        @Volatile
        var crashed = false
            private set

        fun take(line: String) {
            if (LOG.isDebugEnabled) LOG.debug("simulator session: $line")
            // Dropped rather than blocked: a channel nobody is reading must not wedge the thread
            // that reads the helper, and with it every other run on the connection.
            if (!lines.offer(line)) {
                lines.poll()
                lines.offer(line)
            }
        }

        /**
         * Reads until the expected line arrives.
         *
         * A launch that gets no answer is reported as the connection being no use rather than as
         * the run failing, so the caller falls back to `monkeydo` — which knows how to answer a
         * simulator that has stopped taking apps: push again, and restart it if that does not
         * work. Duplicating that here would be a second recovery to keep right; this way there is
         * one. Safe because every wait with a deadline happens before the app is running, so a
         * fallback cannot start an app twice.
         */
        fun expect(
            line: String,
            timeout: Long,
            output: (String) -> Unit,
            givenUp: () -> Boolean = { false },
        ) {
            until(timeout, output, givenUp) { it == line }
                ?: if (givenUp()) return else throw Unavailable(
                    "the simulator did not answer with '$line'",
                )
        }

        /** Reads until [wanted] is satisfied, forwarding the app's output as it goes. */
        fun until(
            timeout: Long,
            output: (String) -> Unit,
            givenUp: () -> Boolean = { false },
            wanted: (String) -> Boolean,
        ): String? {
            val deadline = if (timeout == FOREVER) FOREVER else System.currentTimeMillis() + timeout
            while (true) {
                if (givenUp()) return null
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val line = lines.poll(minOf(left, POLL), TimeUnit.MILLISECONDS) ?: continue
                interpret(line, output)
                if (wanted(line)) return line
            }
        }

        private fun interpret(line: String, output: (String) -> Unit) {
            when {
                line.startsWith(APP) -> output(unescape(line.removePrefix(APP)))

                line.startsWith("event appCrashed ") -> {
                    crashed = true
                    output("\nThe app crashed.\n")
                }

                // A relaunch after a crash is a new life for the same app, and the crash before it
                // is not this run's outcome.
                line.startsWith("event appStarted ") -> crashed = false

                line.startsWith("event appLaunchFailed ") ->
                    throw ExecutionException("The simulator would not launch the app.")

                line.startsWith("event appSignatureCheckFailed ") ->
                    throw ExecutionException(
                        "The simulator rejected the app's signature. Rebuild it with the developer " +
                            "key this project is configured with.",
                    )

                line.startsWith("closed ") -> throw Unavailable("The simulator connection closed.")

                line == DISPLACED -> throw Unavailable(
                    "Something else connected to the simulator and took over its channel.",
                )

                line.startsWith(ERROR) -> throw ExecutionException(line.removePrefix(ERROR))
            }
        }

        override fun close() = connection.forget(this)

        private companion object {
            const val APP = "app "
            const val ERROR = "error "
            const val BACKLOG = 10_000

            /** How long a wait sleeps before looking at its deadline again. */
            const val POLL = 250L
        }
    }

    companion object {
        private val LOG = logger<SimulatorSession>()

        /**
         * The same session outside an IDE, for the live tests.
         *
         * They drive a real simulator from a plain JVM and exercise the shipped code rather than a
         * copy of it, which is the only way a test can say anything about an SDK API that is
         * internal and free to move.
         */
        private val standalone by lazy { SimulatorSession() }

        fun getInstance(): SimulatorSession =
            ApplicationManager.getApplication()?.service<SimulatorSession>() ?: standalone

        /** The JVM the helper runs in: the one the user configured, or whatever is on the path. */
        private fun java() =
            if (ApplicationManager.getApplication() != null) {
                ConnectIqSdkService.getInstance().java()
            } else {
                JavaLocator.resolve(null)
            }

        /** What a run reports when the app did not end cleanly — it crashed, or never closed. */
        const val FAILED = 1

        /** Said by the reader when the helper is gone, so that every wait ends. */
        private const val GONE = "closed -"

        /** What the simulator says to the client it is about to stop talking to. */
        private const val DISPLACED = "event shellDisconnected"

        private const val FOREVER = Long.MAX_VALUE
        // Long enough for a cold machine, short enough that a run which has to fall back to
        // `monkeydo` still feels like a run rather than a hang. Loading a device gets the most,
        // because on a simulator that has just started it is the slowest thing here by far.
        private const val READY_TIMEOUT = 30_000L
        private const val PUSH_TIMEOUT = 30_000L
        private const val DEVICE_TIMEOUT = 60_000L
        private const val START_TIMEOUT = 30_000L
        private const val CLOSE_TIMEOUT = 10_000L

        /**
         * The application id as the simulator says it back.
         *
         * A manifest writes it as thirty-two hex digits and `UUID.toString` writes it with
         * hyphens, so the two spellings have to be made one before an event can be matched to the
         * app it is about.
         */
        fun dashed(uuid: String): String {
            val plain = uuid.replace("-", "")
            if (plain.length != 32) return uuid
            return buildString {
                append(plain, 0, 8).append('-')
                append(plain, 8, 12).append('-')
                append(plain, 12, 16).append('-')
                append(plain, 16, 20).append('-')
                append(plain, 20, 32)
            }
        }

        /**
         * Turns the wire's escaped newlines back into newlines.
         *
         * App output travels as one message per line with newlines written `\n`, which is what
         * lets the protocol be read a line at a time; a console wants it the other way round.
         */
        fun unescape(text: String): String = text.replace("\\n", "\n")
    }
}
