package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.run.SimulatorProcess
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test

/**
 * Starting the simulator as a child process, which is the only way to know anything about it.
 *
 * `open -a` hands the bundle to LaunchServices and returns: nothing to wait on, nothing to read,
 * and stopping it means searching every process on the machine. Executing the program inside the
 * bundle instead was long believed impossible on macOS — the code said so in a comment — and it is
 * not. This is the test that says the belief was wrong, and keeps saying it.
 *
 * Live, because none of it can be faked: the simulator is a GUI application, it takes seconds to
 * come up, and what is being tested is precisely that a real one does.
 */
class SimulatorProcessLiveTest {

    @Test
    fun `the simulator starts as our own process, and says something while it does`() {
        val sdk = LiveSdk.require()
        // Refusing rather than adopting: a simulator that was already up is not one we started, and
        // this test is about the difference.
        assumeFalse(Simulator.isReady(), "a simulator is already running; this test starts its own")

        try {
            assertTrue(Simulator.start(sdk), "the simulator did not start listening")
            assertTrue(
                SimulatorProcess.getInstance().owns(sdk),
                "the simulator is up but not as a child of ours, so `open` was the fallback taken",
            )
            assertTrue(
                SimulatorProcess.getInstance().log(sdk).isNotBlank(),
                "nothing was captured from a simulator we started ourselves",
            )
        } finally {
            Simulator.stop(sdk)
        }
    }

    @Test
    fun `stopping our own simulator needs no search, and closes the port`() {
        val sdk = LiveSdk.require()
        assumeFalse(Simulator.isReady(), "a simulator is already running; this test starts its own")

        assumeTrueStarted(Simulator.start(sdk))

        assertTrue(Simulator.stop(sdk), "the simulator did not stop")
        assertFalse(SimulatorProcess.getInstance().owns(sdk), "it stopped but is still claimed as ours")
        assertFalse(Simulator.isReady(), "the port is still answering after the simulator stopped")
    }

    /**
     * A start that fails is the machine's problem, not the test's.
     *
     * Everything after it would fail too, and reporting three red tests for one missing simulator
     * says less than skipping.
     */
    private fun assumeTrueStarted(started: Boolean) =
        org.junit.jupiter.api.Assumptions.assumeTrue(started, "the simulator did not start on this machine")
}
