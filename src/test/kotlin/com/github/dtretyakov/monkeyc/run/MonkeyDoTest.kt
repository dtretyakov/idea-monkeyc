package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * How a test selection reaches the runner.
 *
 * `monkeydo -t` means "every test", and each name after it narrows the run — which is what makes
 * running one test, or one file's worth of them, possible at all. Getting the shape wrong is the
 * difference between running two tests and running the whole suite while claiming otherwise.
 */
class MonkeyDoTest {

    @Test
    fun `no names runs every test`() {
        val arguments = commandLine(tests = "")

        assertTrue(arguments.contains("-t"))
        assertEquals("-t", arguments.last(), "nothing may follow -t, or it would be read as a name")
    }

    @Test
    fun `each name is an argument of its own`() {
        val arguments = commandLine(tests = "passes fails")

        val afterFlag = arguments.subList(arguments.indexOf("-t") + 1, arguments.size)
        assertEquals(listOf("passes", "fails"), afterFlag)
    }

    @Test
    fun `an app run does not ask for tests`() {
        val arguments = commandLine(tests = "passes", kind = MonkeyCRunKind.APP)

        assertFalse(arguments.contains("-t"))
    }

    private fun commandLine(tests: String, kind: MonkeyCRunKind = MonkeyCRunKind.TESTS): List<String> {
        val options = MonkeyCRunOptions().apply {
            this.kind = kind
            this.tests = tests
        }
        val sdk = ConnectIqSdk(Path.of("/sdk"), Path.of("/sdk/Devices"), Path.of("/sdk"))
        val launch = PreparedLaunch(
            sdk = sdk,
            root = Path.of("/app"),
            device = "fenix7",
            prg = Path.of("/app/bin/App.prg"),
            debugXml = Path.of("/app/bin/App.prg.debug.xml"),
            settingsJson = null,
        )
        return MonkeyDo.commandLine(launch, "java", options).parametersList.list
    }
    @Test
    fun `a refused push is told apart from an app that failed`() {
        // Exactly what SDK 9.2.0 writes with nothing listening on the simulator's ports.
        assertTrue(MonkeyDo.simulatorRefused(2, "Unable to connect to simulator.\n"))
    }

    @Test
    fun `an app failing with the same status is not a refusal`() {
        // 2 is a status an app can exit with. Retrying someone's failing app three times to
        // discover that would be a bug of our own.
        assertFalse(MonkeyDo.simulatorRefused(2, "Error: Symbol Not Found Error\n"))
    }

    @Test
    fun `a run that succeeded is never a refusal`() {
        assertFalse(MonkeyDo.simulatorRefused(0, "Unable to connect to simulator."))
    }

}
