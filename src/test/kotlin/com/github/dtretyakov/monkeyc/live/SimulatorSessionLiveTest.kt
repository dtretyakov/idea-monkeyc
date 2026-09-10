package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.run.session.SimulatorSession
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Running an app on the plugin's own connection to the simulator, and stopping it again.
 *
 * The bug this is here to keep fixed: Stop killed `monkeydo` and the app went on running in the
 * simulator, so the next Debug met it there and timed out. Nothing in a unit test can see that —
 * it is a fact about a GUI application on the other end of a socket — so the assertion is the one
 * thing that proves it either way: after Stop, the same app can be started again on the same
 * connection, which is exactly what used to fail.
 *
 * It also stands guard over an SDK API that is internal and undocumented. The connection is built
 * out of Garmin's own client classes by reflection, and the day an SDK renames one of them this
 * test goes red while the plugin quietly falls back to `monkeydo` — which is the point of having
 * both, but not of leaving it unnoticed.
 */
class SimulatorSessionLiveTest {

    @Test
    fun `an app runs, prints, and is gone once it has been stopped`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val app = buildFixture(sdk, temp)

        val printed = CountDownLatch(1)
        val exit = AtomicInteger(Int.MIN_VALUE)
        val output = StringBuilder()

        val run = Thread {
            exit.set(
                session(sdk, app) { text ->
                    synchronized(output) { output.append(text) }
                    if (output.contains("onUpdate")) printed.countDown()
                },
            )
        }
        run.start()

        assertTrue(
            printed.await(2, TimeUnit.MINUTES),
            "the app never printed anything, so it never really ran: $output",
        )

        SimulatorSession.getInstance().stop()
        run.join(TimeUnit.SECONDS.toMillis(30))

        assertTrue(!run.isAlive, "the run did not end after Stop, so the app never terminated")
        assertEquals(0, exit.get(), "the app did not end cleanly")
    }

    @Test
    fun `the connection survives a stop, and the next run starts on it`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val app = buildFixture(sdk, temp)

        // Twice, because once proves nothing: the failure being tested for is a second run meeting
        // the first one's app still running in the simulator.
        repeat(2) { attempt ->
            val printed = CountDownLatch(1)
            val run = Thread {
                session(sdk, app) { text -> if (text.contains("onUpdate")) printed.countDown() }
            }
            run.start()
            assertTrue(
                printed.await(2, TimeUnit.MINUTES),
                "run ${attempt + 1} never started the app; the one before it was still running",
            )
            SimulatorSession.getInstance().stop()
            run.join(TimeUnit.SECONDS.toMillis(30))
            assertTrue(!run.isAlive, "run ${attempt + 1} did not end after Stop")
        }
    }

    @Test
    fun `the tests of an app run on the same connection and report themselves`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val project = LiveSdk.fixture(temp)
        val device = LiveSdk.device(sdk, DEVICES)
        val built = LiveBuild.run(sdk, project, device, kind = BuildKind.TESTS)
        assumeTrue(built.exitCode == 0, "the fixture's tests did not build: ${built.text}")
        assumeTrue(Simulator.start(sdk), "the simulator did not start")

        val output = StringBuilder()
        SimulatorSession.getInstance().run(
            sdk = sdk,
            prg = built.prg,
            device = device,
            uuid = APPLICATION_ID,
            tests = emptyList(),
            nativePairing = false,
            stopped = { false },
            report = {},
            output = { text -> synchronized(output) { output.append(text) } },
        )

        // The runner's own words, which is what the test tree is built by reading.
        assertTrue(
            output.contains("Executing test"),
            "the test runner said nothing about running any test: $output",
        )
        assertTrue(output.contains("RESULTS"), "the test run never reported a summary: $output")
    }

    /** One run on the session, with the simulator brought up first. */
    private fun session(sdk: ConnectIqSdk, prg: Path, output: (String) -> Unit): Int {
        val device = LiveSdk.device(sdk, DEVICES)
        return SimulatorSession.getInstance().run(
            sdk = sdk,
            prg = prg,
            device = device,
            uuid = APPLICATION_ID,
            tests = null,
            nativePairing = false,
            stopped = { false },
            report = {},
            output = output,
        )
    }

    private fun buildFixture(sdk: ConnectIqSdk, temp: Path): Path {
        val project = LiveSdk.fixture(temp)
        val device = LiveSdk.device(sdk, DEVICES)
        val built = LiveBuild.run(sdk, project, device)
        assumeTrue(built.exitCode == 0, "the fixture did not build: ${built.text}")
        assumeTrue(Simulator.start(sdk), "the simulator did not start")
        return built.prg
    }

    private companion object {
        val DEVICES = listOf("fenix7", "fenix6")

        /** The fixture's own application id, which is how the simulator names it back. */
        const val APPLICATION_ID = "8f14e45fceea167a5a36dedd4bea2543"
    }
}
