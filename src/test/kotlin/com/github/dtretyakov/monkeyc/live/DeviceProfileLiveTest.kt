package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import com.github.dtretyakov.monkeyc.ui.DeviceSelectionSummary
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The columns of the product table, read from the profiles Garmin actually ships.
 *
 * A fixture proves the parsing; only the SDK proves the fields are there and that they differ
 * enough for a column to earn its place. A column every device answers the same way is a column
 * that should be deleted, and this is what would say so.
 */
class DeviceProfileLiveTest {

    @Test
    fun `every device says what its screen is`() {
        val devices = DeviceCatalog(LiveSdk.require().devicesRoot).devices()
        assumeTrue(devices.isNotEmpty(), "no devices are downloaded")

        val silent = devices.filter { it.resolution == null || it.shape == null }
        assertTrue(silent.isEmpty(), "no screen for ${silent.map { it.id }.take(10)}")
        assertTrue(devices.all { it.screen.contains("×") }, "the Screen column would be blank")
    }

    @Test
    fun `the columns earn their place by disagreeing`() {
        val devices = DeviceCatalog(LiveSdk.require().devicesRoot).devices()
        assumeTrue(devices.size > 20, "too few devices to say")

        // Each of these is a column. One distinct value across the catalogue means the column tells
        // nobody anything, and the honest response would be to remove it.
        assertTrue(devices.mapNotNull { it.shape }.distinct().size > 1, "every screen is the same shape")
        assertTrue(devices.mapNotNull { it.bitsPerPixel }.distinct().size > 1, "every screen has one depth")
        assertTrue(devices.mapNotNull { it.displayType }.distinct().size > 1, "every panel is the same")
        assertTrue(devices.map { it.isTouch }.distinct().size > 1, "every device agrees about touch")
        assertTrue(devices.mapNotNull { it.family }.distinct().size > 5, "too few families to be worth counting")
    }

    @Test
    fun `the summary of everything downloaded says all of what it should`() {
        val devices = DeviceCatalog(LiveSdk.require().devicesRoot).devices()
        assumeTrue(devices.size > 20, "too few devices to say")

        val line = DeviceSelectionSummary.of(devices, "watch-app")!!

        assertTrue(line.contains("${devices.size} selected"), line)
        assertTrue(line.contains("resource families"), line)
        // The spread is the entire argument for showing a range: the app must fit the smallest.
        assertTrue(line.contains("memory") && line.contains("–"), line)
        assertTrue(line.contains("without touch"), line)
        assertTrue(line.contains("colour down to"), line)
        println("summary of all downloaded devices: $line")
    }
}
