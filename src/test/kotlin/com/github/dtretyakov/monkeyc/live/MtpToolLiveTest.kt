package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.run.MtpTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Runs the real `mtp-rs` and reads its real answer.
 *
 * No watch is needed for the thing this catches. The field names come from the tool's `DeviceRow`
 * struct, and a rename upstream would leave our parser quietly returning defaults for everything —
 * a device with no product and no serial, which looks like a detection problem rather than a
 * parsing one. Running the real binary and insisting the contract still holds is how that arrives
 * as a red test.
 *
 * Skipped when the tool is not installed, like every other live test here.
 */
class MtpToolLiveTest {

    private fun tool(): Path? =
        MtpTool.candidates(Path.of(System.getProperty("user.home")))
            .firstOrNull { it.isRegularFile() && it.isExecutable() }

    @Test
    fun `listing devices answers JSON this plugin can read`() {
        val tool = tool()
        assumeTrue(tool != null, "mtp-rs is not installed")

        val process = ProcessBuilder(listOf(tool.toString()) + MtpTool.deviceArguments())
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "mtp-rs did not finish")

        // Zero devices is the expected answer on a machine with nothing plugged in, and it is not
        // a failure: the command exits 0 with an empty array. Only commands that need a device
        // exit 2, which is why detection reads the JSON rather than the exit code.
        assertEquals(0, process.exitValue(), "listing devices should succeed even with none: $output")
        assertTrue(output.trim().startsWith("["), "expected a JSON array, got: ${output.take(200)}")

        val devices = MtpTool.parseDevices(output)
        // With a watch attached this says more; without one it still proves the shape parses.
        devices.forEach {
            assertTrue(
                it.serial_number != null || it.product != null || it.manufacturer != null,
                "a device with none of the fields we read suggests they were renamed: $it",
            )
        }
    }

    @Test
    fun `a command that needs a device fails the way we expect when there is none`() {
        val tool = tool()
        assumeTrue(tool != null, "mtp-rs is not installed")

        val process = ProcessBuilder(tool.toString(), "info").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(30, TimeUnit.SECONDS)

        assumeTrue(process.exitValue() != 0, "a device is attached, so this says nothing")
        assertEquals(MtpTool.NO_DEVICE, process.exitValue(), "the no-device exit code: $output")
        assertTrue(
            MtpTool.describeFailure(process.exitValue(), output).contains("No Garmin device"),
            "our words for it should be about a missing device",
        )
    }
}
