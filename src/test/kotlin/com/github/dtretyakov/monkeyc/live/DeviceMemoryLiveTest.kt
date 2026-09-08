package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.sdk.AppTypes
import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The memory limits, read from the device data the SDK Manager actually downloaded.
 *
 * Worth a live test rather than a fixture, because the value of the figure is that it is Garmin's
 * and not ours. If a later SDK renames an app type or stops publishing `memoryLimit`, the plugin
 * would quietly stop reporting memory at all — silence being the safe answer everywhere else in
 * this code — and this is what would notice.
 */
class DeviceMemoryLiveTest {

    @Test
    fun `every downloaded device publishes a memory limit for something`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.isNotEmpty(), "no devices are downloaded")

        val silent = devices.filter { it.memoryLimits.isEmpty() }
        assertTrue(silent.isEmpty(), "no memory limits for ${silent.map { it.id }}")
    }

    @Test
    fun `the app types a manifest can declare are the ones the catalogue knows`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.isNotEmpty(), "no devices are downloaded")

        // Every kind of app a manifest can name has to reach a real budget on some device, or the
        // translation between the manifest's spelling and the catalogue's has gone stale.
        listOf("watch-app", "watchface", "datafield", "widget", "audio-content-provider-app").forEach { type ->
            val known = devices.any { it.memoryLimitFor(type) != null }
            assertTrue(known, "no downloaded device has a budget for '$type' (${AppTypes.catalogueName(type)})")
        }
    }

    @Test
    fun `the limits are in the range Garmin documents`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.isNotEmpty(), "no devices are downloaded")

        // 16 KB is the smallest budget on the oldest data field; nothing on a watch has megabytes
        // to spare. A figure outside this is a unit that changed under us.
        val odd = devices.flatMap { it.memoryLimits.values }.filter { it < 16_384 || it > 8L * 1024 * 1024 }
        assertTrue(odd.isEmpty(), "implausible memory limits: ${odd.distinct().sorted()}")
    }
}
