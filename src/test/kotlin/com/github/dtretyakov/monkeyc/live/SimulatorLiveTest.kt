package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Finding the simulator that is actually running, whichever SDK started it.
 *
 * The scoped-to-one-SDK version of this looked principled and broke the case that happens: the
 * SDK Manager installs a new SDK and makes it current while yesterday's simulator is still open.
 * Stop then found nothing and said so with the window in plain view, and Start saw the port taken
 * and did nothing at all.
 */
class SimulatorLiveTest {

    @Test
    fun `a running simulator is found whichever SDK started it`() {
        val sdk = LiveSdk.require()
        assumeTrue(Simulator.isReady(), "no simulator is listening")

        val live = Simulator.runningSdk(sdk.dataRoot)

        assertTrue(live != null, "a simulator holds the port but no process was matched to an SDK")
        assertTrue(
            ConnectIqSdk.installed(sdk.dataRoot).any { it == live },
            "the simulator was traced to $live, which is not one of the installed SDKs",
        )
    }

    @Test
    fun `a simulator from another SDK is reported as a conflict`() {
        val sdk = LiveSdk.require()
        assumeTrue(Simulator.isReady(), "no simulator is listening")

        val live = Simulator.runningSdk(sdk.dataRoot)
        assumeTrue(live != null, "the running simulator could not be traced")

        // Whichever way round this machine happens to be, the two answers have to agree.
        val conflict = Simulator.conflictingSdk(sdk)
        if (live == sdk.root) {
            assertTrue(conflict == null, "the current SDK's own simulator is not a conflict")
        } else {
            assertTrue(conflict == live, "a simulator from $live must be reported against ${sdk.root}")
        }
    }
}
