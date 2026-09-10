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

    private fun device(id: String, touch: Boolean = true, memory: Long = 65_536L) = ConnectIqDevice(
        id = id,
        displayName = id,
        group = null,
        family = "round-240x240",
        isTouch = touch,
        sdkVersion = null,
        memoryLimits = mapOf("watchApp" to memory),
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

    @Test
    fun `sorting by memory puts the device the app has to fit inside at the top`() {
        // The whole argument for a table over a list of checkboxes, and the one the manifest form
        // and the Marketplace listing both make out loud. A table whose headers do not sort is a
        // list of checkboxes with columns.
        val table = ProductTable(
            devices = listOf(
                device("roomy", memory = 1024L * 1024),
                device("tight", memory = 64L * 1024),
                device("middling", memory = 256L * 1024),
            ),
            selected = emptySet(),
            appType = "watch-app",
        )
        val sorter = table.table.rowSorter!!

        sorter.toggleSortOrder(MEMORY_COLUMN)

        val order = (0 until table.table.rowCount)
            .map { table.model.getItem(table.table.convertRowIndexToModel(it)).device.id }
        assertEquals(listOf("tight", "middling", "roomy"), order)
    }

    @Test
    fun `the table can be made narrower than the widths it would prefer`() {
        // `ColumnInfo.getWidth` sets a column's minimum as well as its preferred width, so the
        // widths declared for these seven columns added up to a table that refused to be narrower
        // than about 900 pixels. In an editor narrower than that the last column — Memory, the one
        // worth having — was cut off by the edge rather than squeezed, and nothing said so.
        val table = ProductTable(listOf(device("venu2")), emptySet(), "watch-app")

        val minimum = table.table.columnModel.columns.toList().sumOf { it.minWidth }
        assertTrue(minimum < 400, "the table cannot be drawn narrower than $minimum px")
    }

    private companion object {
        /** Checkbox, ID, Device, Screen, Colors, Panel, Input, Memory. */
        const val MEMORY_COLUMN = 7
    }
}
