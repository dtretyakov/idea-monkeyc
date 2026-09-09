package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which door the target popup offers, and when.
 *
 * The list there is the manifest's products intersected with what has been downloaded, so "my watch
 * is not in this list" has two remedies and the popup used to offer only the second one — the SDK
 * Manager, unconditionally, including for projects that had every declared device on disk already.
 */
class DeviceMenuTest {

    private fun device(id: String) = ConnectIqDevice(
        id = id,
        displayName = id,
        group = null,
        family = null,
        isTouch = true,
        sdkVersion = null,
        memoryLimits = emptyMap(),
    )

    @Test
    fun `everything declared is downloaded, so the SDK Manager is not the answer`() {
        val installed = listOf(device("fenix7"), device("venu2"))

        val offer = DeviceMenu.of(declared = listOf("fenix7"), installed = installed, buildable = listOf(device("fenix7")))

        assertFalse(offer.sdkManager, "offered the SDK Manager to a project with nothing missing")
        assertNull(offer.empty)
    }

    @Test
    fun `a declared device that is not downloaded is exactly what the SDK Manager is for`() {
        val offer = DeviceMenu.of(
            declared = listOf("fenix7", "epix2pro47mm"),
            installed = listOf(device("fenix7")),
            buildable = listOf(device("fenix7")),
        )

        assertTrue(offer.sdkManager)
        // Still a list, so nothing to say in place of one.
        assertNull(offer.empty)
    }

    @Test
    fun `nothing downloaded at all says so`() {
        val offer = DeviceMenu.of(declared = listOf("fenix7"), installed = emptyList(), buildable = emptyList())

        assertTrue(offer.sdkManager)
        assertEquals("No devices are downloaded", offer.empty)
    }

    @Test
    fun `declared but none of them downloaded names the count, because that is the remedy`() {
        val offer = DeviceMenu.of(
            declared = listOf("fenix7", "venu2"),
            installed = listOf(device("fr965")),
            buildable = emptyList(),
        )

        assertTrue(offer.sdkManager)
        assertEquals("None of the 2 declared devices is downloaded", offer.empty)
    }

    @Test
    fun `nothing declared and nothing offered is about the app, not the machine`() {
        // A barrel, or an app type none of the downloaded devices runs. Pointing at the SDK
        // Manager here would be answering a question nobody asked: the devices are present.
        val offer = DeviceMenu.of(declared = emptyList(), installed = listOf(device("fr965")), buildable = emptyList())

        assertFalse(offer.sdkManager)
        assertEquals("No downloaded device runs this app", offer.empty)
    }
}
