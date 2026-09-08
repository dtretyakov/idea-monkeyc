package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
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
class ProductTable(
    devices: List<ConnectIqDevice>,
    selected: Set<String>,
    appType: String?,
    /**
     * Ids the manifest declares that the SDK Manager has not downloaded.
     *
     * They have to be rows, ticked, or the first edit would write them out of the manifest — the
     * table can only save what it can see. A preview device, or one downloaded on a colleague's
     * machine, is a perfectly ordinary thing to find in a manifest.
     */
    undownloaded: List<String> = emptyList(),
) {

    /** One row: a device and whether it is declared. */
    class Row(val device: ConnectIqDevice, var selected: Boolean, val downloaded: Boolean = true)

    private val rows = devices.map { Row(it, it.id in selected) } +
        undownloaded.map { Row(placeholder(it), selected = true, downloaded = false) }

    val model: ListTableModel<Row> = ListTableModel(
        arrayOf(
            checkbox(),
            device(),
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

    /**
     * Narrows the rows shown, keeping the ticks of the ones hidden.
     *
     * The ticks live on the rows rather than in the table, for the same reason a filtered checkbox
     * list has to keep them apart from the widget: filtering empties and refills what is on screen,
     * and reading the state off it would forget everything not currently visible.
     */
    fun filter(text: String) {
        val needle = text.trim().lowercase()
        model.items = if (needle.isEmpty()) {
            rows
        } else {
            rows.filter { row ->
                needle in row.device.id.lowercase() || needle in row.device.displayName.lowercase()
            }
        }
    }

    /** Ticks or unticks every row currently shown, which is what a bulk action means. */
    fun setVisible(wanted: (ConnectIqDevice) -> Boolean) {
        model.items.forEach { it.selected = wanted(it.device) }
        model.fireTableDataChanged()
        onChanged()
    }

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

    /** The name, and for a device this machine does not have, the fact that it does not. */
    private fun device() = object : ColumnInfo<Row, String>("Device") {
        override fun valueOf(item: Row): String = if (item.downloaded) {
            "${item.device.displayName}  (${item.device.id})"
        } else {
            "${item.device.id}  (not downloaded)"
        }

        override fun getWidth(table: javax.swing.JTable): Int = DEVICE_WIDTH
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

    /** Set by the caller so the summary line and the tab title can follow the ticks. */
    var onChanged: () -> Unit = {}

    /**
     * The whole control: bulk actions, a search box, the table, and the line that says what the
     * selection costs.
     *
     * Laid out the way the neighbouring lists already are, so the Products tab does not look like
     * a different application from the Permissions one beside it.
     */
    fun panel(actions: List<Pair<String, (ConnectIqDevice) -> Boolean>>, footer: JComponent): JComponent =
        JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(8)

            val header = JPanel(BorderLayout(0, JBUI.scale(4)))
            if (actions.isNotEmpty()) {
                header.add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                        actions.forEach { (title, wanted) -> add(ActionLink(title) { setVisible(wanted) }) }
                    },
                    BorderLayout.NORTH,
                )
            }
            header.add(
                SearchTextField(false).also { search ->
                    search.addDocumentListener(
                        object : DocumentAdapter() {
                            override fun textChanged(event: DocumentEvent) = filter(search.text)
                        },
                    )
                },
                BorderLayout.CENTER,
            )
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

    private companion object {
        /** A row for an id with no profile behind it: every column but the name is blank. */
        fun placeholder(id: String) = ConnectIqDevice(
            id = id,
            displayName = id,
            group = null,
            family = null,
            isTouch = false,
            sdkVersion = null,
            memoryLimits = emptyMap(),
        )

        const val DEVICE_WIDTH = 220
        const val CHECKBOX_WIDTH = 28
        const val MEMORY_WIDTH = 90
        const val NAME_COLUMN = 1
    }
}
