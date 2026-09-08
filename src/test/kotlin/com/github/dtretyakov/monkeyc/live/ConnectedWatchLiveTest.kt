package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.run.ConnectedWatch
import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Matching against every device the SDK Manager actually downloaded.
 *
 * A fixture proves the rule on names somebody chose; this proves it on Garmin's, which are the
 * awkward ones — 171 of the 173 here carry a trademark mark or a diacritic. What it really checks
 * is that no two devices normalise to the same string, because a matcher that silently picks the
 * first of two is worse than one that finds nothing.
 */
class ConnectedWatchLiveTest {

    @Test
    fun `every downloaded device is found by its own plain name`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.isNotEmpty(), "no devices are downloaded")

        // A watch reports its model without the marketing decoration, so this is the closest thing
        // to what a USB descriptor would say.
        val undecorated = devices.associateWith { it.displayName.replace(Regex("[®™]"), "").trim() }

        val missed = undecorated.filter { (device, plain) ->
            ConnectedWatch.match(plain, devices)?.id != device.id
        }
        assertTrue(missed.isEmpty(), "not matched by their own names: ${missed.keys.map { it.id }.take(10)}")
    }

    @Test
    fun `no two devices collapse to the same name`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.isNotEmpty(), "no devices are downloaded")

        // Matching answers the first device it finds. If two share a normalised name, whichever is
        // first wins silently and the other can never be recognised.
        val collisions = devices
            .groupBy { ConnectedWatch.match(it.displayName, devices)?.id }
            .filterValues { it.size > 1 }

        assertEquals(emptyMap<String?, Any>(), collisions, "these share a name after normalising")
    }
}
