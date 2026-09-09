package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.AppTypes
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import java.util.Locale

/**
 * What a set of chosen devices commits you to, in one line.
 *
 * The list of ticked boxes answers "which watches"; it answers none of the questions that decide
 * whether the choice is a good one. Two of those have real consequences and neither is shown
 * anywhere in this ecosystem:
 *
 * **How many resource families.** Devices are grouped into families like `round-240x240`, and a
 * family is a set of layouts and drawables to draw, tune and keep working. Ticking forty devices
 * can mean six of those or twenty, and the count is the difference between an afternoon and a
 * fortnight.
 *
 * **The smallest memory budget.** An app has to fit the *tightest* device it declares, not the
 * roomiest — a watch app's budget runs from 64 KB to 2304 KB across the devices Garmin ships, a
 * thirty-six-fold spread. The lower number is the one that actually constrains the code, and until
 * now it took a spreadsheet to find.
 *
 * The rest of the line is what a design has to survive: devices with no touch, and colour depths
 * below the one the artwork was drawn for.
 */
object DeviceSelectionSummary {

    /**
     * The line, or null when there is nothing chosen to say anything about.
     *
     * Each clause is dropped when the data cannot support it rather than shown as a zero: a
     * profile that does not say how many colours a screen has should not be reported as having
     * none.
     *
     * [selected] is the devices whose profiles this machine actually has; [total] is how many are
     * ticked. They differ when the manifest declares a device the SDK Manager never downloaded,
     * and every clause but the count has to be computed without it — a device we know nothing
     * about is not a device we know has no touchscreen.
     */
    fun of(
        selected: List<ConnectIqDevice>,
        manifestAppType: String?,
        total: Int = selected.size,
    ): String? {
        if (total == 0) return null

        val parts = buildList {
            add("$total selected")
            families(selected)?.let { add(it) }
            memory(selected, manifestAppType)?.let { add(it) }
            withoutTouch(selected)?.let { add(it) }
            colours(selected)?.let { add(it) }
        }
        return parts.joinToString(SEPARATOR)
    }

    /** Resource families, which is the number of layout variants the choice signs you up for. */
    private fun families(selected: List<ConnectIqDevice>): String? {
        val families = selected.mapNotNull { it.family }.distinct()
        if (families.isEmpty()) return null
        return "${families.size} resource ${if (families.size == 1) "family" else "families"}"
    }

    /**
     * The memory budget, as a range, because only the bottom of it constrains anything.
     *
     * A single figure would be a choice between a number that misleads and a number that is not
     * the one you have to fit.
     */
    private fun memory(selected: List<ConnectIqDevice>, manifestAppType: String?): String? {
        if (AppTypes.catalogueName(manifestAppType) == null) return null
        val limits = selected.mapNotNull { it.memoryLimitFor(manifestAppType) }
        if (limits.isEmpty()) return null

        val smallest = limits.min()
        val largest = limits.max()
        return if (smallest == largest) {
            "memory ${kilobytes(smallest)}"
        } else {
            "memory ${kilobytes(smallest)}–${kilobytes(largest)}"
        }
    }

    private fun withoutTouch(selected: List<ConnectIqDevice>): String? =
        selected.count { !it.isTouch }.takeIf { it > 0 }?.let { "$it without touch" }

    /**
     * Colour depths below the deepest chosen.
     *
     * Said as "down to N-bit" rather than as a list, because the decision it informs is whether the
     * artwork survives the worst screen in the set.
     */
    private fun colours(selected: List<ConnectIqDevice>): String? {
        val depths = selected.mapNotNull { it.bitsPerPixel }.distinct()
        if (depths.size < 2) return null
        return "colour down to ${depths.min()}-bit"
    }

    private fun kilobytes(bytes: Long): String = "%d KB".format(Locale.ROOT, bytes / 1024)

    private const val SEPARATOR = " · "
}
