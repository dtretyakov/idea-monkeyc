package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the table gives back, which is what gets written to the manifest.
 *
 * The table is the whole of the product list: whatever it hands back replaces what was there. So
 * the interesting cases are not the ticking but the rows that carry no profile — a device the
 * manifest declares that the SDK Manager never downloaded. It has to survive a save, and it has to
 * stay out of every statement made about the selection.
 */
class ProductTableTest {

    private fun device(id: String, touch: Boolean = true) = ConnectIqDevice(
        id = id,
        displayName = id,
        group = null,
        family = "round-240x240",
        isTouch = touch,
        sdkVersion = null,
        memoryLimits = mapOf("watchApp" to 65_536L),
    )

    @Test
    fun `a declared device with no profile survives a save`() {
        val table = ProductTable(
            devices = listOf(device("venu2")),
            selected = setOf("venu2", "epix2pro47mm"),
            appType = "watch-app",
            undownloaded = listOf("epix2pro47mm"),
        )

        assertEquals(listOf("venu2", "epix2pro47mm"), table.selected())
    }

    @Test
    fun `a device with no profile is not described`() {
        // Its `isTouch` is false because there is nothing to say otherwise, not because the watch
        // has no touchscreen. Reading anything off it is inventing a fact.
        val table = ProductTable(
            devices = listOf(device("venu2", touch = true)),
            selected = setOf("venu2", "epix2pro47mm"),
            appType = "watch-app",
            undownloaded = listOf("epix2pro47mm"),
        )

        assertEquals(listOf("venu2"), table.selectedDevices().map { it.id })

        val line = DeviceSelectionSummary.of(
            table.selectedDevices(),
            "watch-app",
            table.selected().size,
        )!!
        assertTrue(line.startsWith("2 selected"), line)
        assertTrue(!line.contains("without touch"), line)
    }
}
