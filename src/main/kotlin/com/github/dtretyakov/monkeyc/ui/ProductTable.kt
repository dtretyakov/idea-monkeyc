package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.util.Locale

/**
 * The devices a project can declare, as a table rather than a list of names.
 *
 * A hundred and seventy-three checkboxes reading "Venu® 2 (venu2)" answer one question — is this
 * watch in the list — and hide every question that decides whether it should be. The four that
 * matter are the ones a developer would otherwise look up one device at a time on Garmin's
 * website: the screen a layout has to fit, the colour depth artwork has to survive, whether there
 * is a touchscreen to tap, and the memory the code has to fit.
 *
 * A sortable table is what the platform uses for exactly this shape of problem — pick many from a
 * catalogue with attributes — and sorting is most of the value: ordering by memory puts the device
 * that will actually constrain the app at the top, which is not something a name-ordered list can
 * ever show.
 */
class ProductTable(devices: List<ConnectIqDevice>, selected: Set<String>, appType: String?) {

    /** One row: a device and whether it is declared. */
    class Row(val device: ConnectIqDevice, var selected: Boolean)

    private val rows = devices.map { Row(it, it.id in selected) }

    val model: ListTableModel<Row> = ListTableModel(
        arrayOf(
            checkbox(),
            text("Device", 220) { "${it.displayName}  (${it.id})" },
            text("Screen", 150) { it.screen },
            text("Colours", 80) { it.bitsPerPixel?.let { bits -> "$bits-bit" }.orEmpty() },
            text("Panel", 80) { it.displayType.orEmpty() },
            text("Input", 130) { it.input },
            memory(appType),
        ),
        rows.toMutableList(),
    )

    val table: TableView<Row> = TableView(model).apply {
        setShowGrid(false)
        rowSelectionAllowed = true
        // Sorted by name to begin with, because that is how someone looks for a watch they own.
        // Every other order is one click away, and ordering by memory is the interesting one.
        rowSorter?.toggleSortOrder(NAME_COLUMN)
    }

    fun selected(): List<String> = rows.filter { it.selected }.map { it.device.id }

    fun selectedDevices(): List<ConnectIqDevice> = rows.filter { it.selected }.map { it.device }

    /** Ticks or unticks everything currently shown, which is what the bulk buttons act on. */
    fun setAll(matching: (ConnectIqDevice) -> Boolean, value: Boolean) {
        rows.forEach { if (matching(it.device)) it.selected = value }
        model.fireTableDataChanged()
    }

    private fun checkbox() = object : ColumnInfo<Row, Boolean>("") {
        override fun valueOf(item: Row): Boolean = item.selected
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: Boolean?) {
            item.selected = value == true
            onChanged()
        }

        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun getWidth(table: javax.swing.JTable): Int = CHECKBOX_WIDTH
    }

    private fun text(title: String, width: Int, value: (ConnectIqDevice) -> String) =
        object : ColumnInfo<Row, String>(title) {
            override fun valueOf(item: Row): String = value(item.device)
            override fun getWidth(table: javax.swing.JTable): Int = width
        }

    /**
     * Memory as a number that sorts, not as text that sorts alphabetically.
     *
     * "1024 KB" before "64 KB" is the sort a string column gives, and it is worse than no column at
     * all: the whole point of this one is to find the device the app has to fit inside.
     */
    private fun memory(appType: String?) = object : ColumnInfo<Row, Long>("Memory") {
        override fun valueOf(item: Row): Long = item.device.memoryLimitFor(appType) ?: 0

        override fun getRenderer(item: Row): javax.swing.table.TableCellRenderer =
            object : javax.swing.table.DefaultTableCellRenderer() {
                init {
                    horizontalAlignment = javax.swing.SwingConstants.RIGHT
                }

                override fun setValue(value: Any?) {
                    val bytes = (value as? Long) ?: 0
                    text = if (bytes > 0) "%d KB".format(Locale.ROOT, bytes / 1024) else ""
                }
            }

        override fun getComparator(): Comparator<Row> = compareBy { it.device.memoryLimitFor(appType) ?: 0 }
        override fun getWidth(table: javax.swing.JTable): Int = MEMORY_WIDTH
    }

    /** Set by the dialog so the summary line can follow the ticks. */
    var onChanged: () -> Unit = {}

    private companion object {
        const val CHECKBOX_WIDTH = 28
        const val MEMORY_WIDTH = 90
        const val NAME_COLUMN = 1
    }
}
