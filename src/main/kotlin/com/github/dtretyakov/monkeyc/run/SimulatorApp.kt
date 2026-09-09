package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Stopping the app that is running in the simulator, as opposed to stopping the simulator.
 *
 * Pressing Stop used to kill `monkeydo` and nothing else, and `monkeydo` is the program that pushes
 * the app and relays its output — not the app. So the app went on running inside the simulator with
 * nothing attached to it: measured at update 3846 of a counter the developer had stopped watching
 * long before. The next Run mostly coped, because the simulator accepts a second push; the next
 * Debug did not, and failed with "Failed to connect to the simulator: Timeout" while the previous
 * app's output was still arriving in the new console.
 *
 * The SDK can close it. `DeviceChannel.closeApp(UUID)` in `monkeybrains.jar` writes
 * `[0]closeApp<uuid>` — the id with its hyphens removed — to the shell's `ciq` sub-channel, which
 * is the same channel `monkeydo` pushes over. Sending that by hand stops the app: measured at 19
 * and 29 output lines before, and none at all after.
 *
 * Stopping the app rather than the simulator is also what every neighbouring toolchain does. In
 * Android Studio and Xcode, Stop stops what you ran; the device it ran on is infrastructure and
 * outlives it. Restarting the simulator would cost seconds and lose the device, the settings and
 * the app data on every single Stop.
 */
object SimulatorApp {

    /**
     * The close that is still in flight, so a launch can wait for it.
     *
     * Stop must not block the UI, so the close runs on a pooled thread — and a developer who
     * presses Run straight afterwards would otherwise race it: the new app starts, the late
     * `closeApp` arrives, and it names the same id, so it closes the app that just started. The
     * launch waits for this instead, which costs nothing when nothing is pending.
     */
    @Volatile
    private var pending: Future<*>? = null

    /** Sends the close in the background and returns at once, for callers that must not block. */
    fun closeLater(sdk: ConnectIqSdk, applicationId: String) {
        pending = AppExecutorUtil.getAppExecutorService().submit { close(sdk, applicationId) }
    }

    /**
     * Waits for a close that Stop started, so a push cannot overtake it.
     *
     * Bounded, and a timeout is not worth reporting: the worst case is the race that was there
     * before, and the run has better things to fail on than this.
     */
    fun awaitClose(timeoutMillis: Long = 6_000) {
        val inFlight = pending ?: return
        runCatching { inFlight.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        pending = null
    }

    /**
     * Asks the simulator to close the app with this id, and says whether the ask went through.
     *
     * True means the shell accepted the message, not that the app is gone — the simulator does not
     * answer a close. It runs on the way out of a run, where there is nothing useful to do about a
     * failure except carry on stopping, so a failure is logged and no more.
     */
    fun close(sdk: ConnectIqSdk, applicationId: String, timeoutMillis: Int = 3_000): Boolean {
        val id = applicationId.replace("-", "").trim()
        if (id.isEmpty()) return false

        val command = GeneralCommandLine(sdk.shell.toString(), CHANNEL)
            .withCharset(StandardCharsets.UTF_8)

        return runCatching {
            val handler = CapturingProcessHandler(command)
            // The message goes in on standard input: `ciq` opens the channel and relays what it is
            // given. Passing it as an argument does not work — the shell reads arguments as its own
            // commands and answers "Command not found".
            handler.processInput.use { it.write("[0]closeApp$id\n".toByteArray(StandardCharsets.UTF_8)) }
            handler.runProcess(timeoutMillis, true)
            true
        }.getOrElse {
            LOG.info("Could not ask the simulator to close $id", it)
            false
        }
    }

    /** The shell sub-channel the simulator's Connect IQ side listens on. */
    private const val CHANNEL = "ciq"

    private val LOG = logger<SimulatorApp>()
}
