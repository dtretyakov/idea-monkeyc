package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.run.SimulatorApp
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Asking the simulator to close the app running in it.
 *
 * Stop killed `monkeydo`, which is the program that pushes the app and relays its output — not the
 * app. The app went on running inside the simulator with nothing attached to it, and the next Debug
 * failed with "Failed to connect to the simulator: Timeout" while the previous app's output was
 * still arriving in the new console.
 *
 * **What this covers:** that the message still reaches the simulator — the right binary, the right
 * sub-channel, the right shape of argument. That is what an SDK update would break, and it would
 * break silently, because nothing answers a close.
 *
 * **What it does not cover:** that the app then stops. The only signal for that is the app's own
 * output, and a Connect IQ app redraws when it feels like it — on a freshly started simulator,
 * twice in twenty seconds; on one that has been up a while, forty times in eight. A test counting
 * those lines goes red on a slow morning, and a live suite nobody trusts is worse than one that
 * admits its edges. Measured by hand instead, on SDK 9.2.0: after the pusher was killed the app
 * printed 19 and then 29 lines in consecutive six-second windows, and after `[0]closeApp<id>` it
 * printed none, twice.
 */
class SimulatorAppLiveTest {

    @Test
    fun `the simulator accepts the message that closes an app`() {
        val sdk = LiveSdk.require()
        assumeTrue(Simulator.start(sdk), "the simulator did not start")

        try {
            // Closing an app that is not running is not an error, and the subject here is the
            // channel rather than the app: a wrong sub-channel name comes back "Command not found",
            // a wrong binary does not run at all, and both of those arrive as false.
            assertTrue(
                SimulatorApp.close(sdk, "8f14e45fceea167a5a36dedd4bea2543"),
                "the simulator's shell would not take the close message",
            )
        } finally {
            Simulator.stop(sdk)
        }
    }

    @Test
    fun `an empty id never reaches the simulator`() {
        val sdk = LiveSdk.require()

        assertFalse(SimulatorApp.close(sdk, "   "), "an empty id should not be sent at all")
    }
}
